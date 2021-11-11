package bio.ferlab.fhir.etl.task

import bio.ferlab.fhir.Fhavro
import bio.ferlab.fhir.etl.config.{Config, FhirRequest}
import bio.ferlab.fhir.etl.keycloak.Authentication.buildKeycloakAuthentication
import bio.ferlab.fhir.etl.keycloak.KeyCloak
import bio.ferlab.fhir.etl.model.FhirBulkResource
import bio.ferlab.fhir.etl.request.RequestUtils
import bio.ferlab.fhir.etl.s3.S3Utils.{buildKey, buildS3Client, writeFile}
import bio.ferlab.fhir.schema.repository.SchemaMode
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.hl7.fhir.r4.model.DomainResource
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{Json, Reads}
import software.amazon.awssdk.services.s3.S3Client
import sttp.client3.{HttpURLConnectionBackend, UriContext, basicRequest}
import sttp.model.StatusCode

import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.SeqHasAsJava

class FhavroBulkExporter(config: Config) {

  val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  implicit val keyCloak: KeyCloak = buildKeycloakAuthentication(config.keycloakConfig)

  implicit val s3Client: S3Client = buildS3Client(config.awsConfig)

  implicit val exportOutputReads: Reads[ExportResult] = Json.reads[ExportResult]

  def requestBulkExportFor(request: FhirRequest): String = {
    LOGGER.info(s"Requesting Bulk Export for ${request.`type`}")
    val backend = HttpURLConnectionBackend()
    val response = basicRequest
      .headers(Map(
        "Cookie" -> s"${keyCloak.getAuthentication}",
        "Prefer" -> "respond-async"
      ))
      .get(RequestUtils.buildAsyncUri(config.fhirConfig.baseUrl, request))
      .send(backend)

    backend.close

    response.code match {
      case StatusCode.Accepted =>
        response.headers
          .find(_.name == "Content-Location")
          .map(_.value)
          .getOrElse(throw new RuntimeException("Bulk export was accepted by the server but no polling url was found in the header [Content-Location]"))
      case _ => throw new RuntimeException(s"Export returned: ${response.toString}")
    }
  }

  def checkPollingStatusOnce(pollingUrl: String): Option[List[(String, String)]] = {
    val backend = HttpURLConnectionBackend()
    val response = basicRequest
      .headers(Map(
        "Cookie" -> s"${keyCloak.getAuthentication}"
      ))
      .get(uri"$pollingUrl")
      .send(backend)

    backend.close
    response.code match {
      case StatusCode.Ok =>
        Some(
          (Json.parse(response.body.right.get) \ "output")
            .as[Seq[ExportResult]]
            .map(output => output.`type` -> output.url)
            .toList
        )
      case StatusCode.Accepted =>
        LOGGER.debug(s"Bulk Export status: ${response.headers.find(_.name == "X-Progress").map(_.value).getOrElse("unknown")}\r")
        None
      case s =>
        LOGGER.info(s"Unexpected status code: $s")
        LOGGER.info(s"With body: ${response.body}")
        None
    }
  }

  def uploadFiles(resource: FhirBulkResource, schemaPath: String, files: List[(String, String)]): Unit = {
    LOGGER.info(s"Converting resource: ${resource.name}")
    val key = buildKey(resource)
    val file = convertResources(resource, schemaPath, files)
    writeFile(config.awsConfig.bucketName, key, file)
    LOGGER.info(s"Uploaded File ${resource.schema} successfully!")
  }

  private def convertResources(resource: FhirBulkResource, schemaPath: String, files: List[(String, String)]): File = {
    val schemaRelativePath = s"$schemaPath/${resource.schema}"

    LOGGER.info(s"--- Loading schema: ${resource.schema} from ./$schemaRelativePath")
    val schema = Fhavro.loadSchema(schemaRelativePath, SchemaMode.ADVANCED)

    // TODO: write to temporary file as soon getFileContent returns something OR even write directly to S3 as stream (if possible)?
    //  (would there be any performance improvement?)
    LOGGER.info(s"--- Fetching File Contents for ${resource.name}")
    val fileContents: List[String] = getFilesContent(files)

    LOGGER.info(s"--- Converting File Content to GenericRecord for ${resource.name}")
    val genericRecords: List[GenericRecord] = convertFileContentToGenericRecord(resource.name, schema, fileContents)

    LOGGER.info(s"--- Serializing Generic Records for ${resource.name}")
    Files.createDirectories(Paths.get("./tmp"))
    val file = new File(s"./tmp/${resource.name}.avro")
    val fileOutputStream = new FileOutputStream(file)
    Fhavro.serializeGenericRecords(schema, genericRecords.asJava, fileOutputStream)
    fileOutputStream.close()
    file
  }

  private def getFilesContent(files: List[(String, String)]): List[String] = {
    val total = files.length
    val progress = new AtomicInteger()

    files.foldLeft(List[String]())((a,b) => {
      logProgress("fetch", progress, total)
      getFileContent(b._2).split("\\n")
        .toList
        .filter(!_.isBlank)
    })


//    files.map(file => {
//      logProgress("fetch", progress, total)
//      getFileContent(file._2)
//    }).flatMap(_.split("\\n"))
//      .filter(!_.isBlank)
//    files.map(_._2)
//      .map(binaryUrl => {
//        logProgress("fetch", progress, total)
//        getFileContent(binaryUrl)
//      })
//      .flatMap(_.split("\\n"))
//      .filter(!_.isBlank)
  }

  private def getFileContent(url: String): String = {
    val backend = HttpURLConnectionBackend()
    val response = basicRequest
      .headers(Map(
        "Cookie" -> s"${keyCloak.getAuthentication}"
      ))
      .get(uri"$url")
      .send(backend)
    backend.close
    response.body.right.get
  }

  private def convertFileContentToGenericRecord(resourceName: String, schema: Schema, fileContents: List[String]): List[GenericRecord] = {
    val total = fileContents.length
    val progress = new AtomicInteger()
    fileContents.map(fileContent => {
      logProgress("convert", progress, total)
      val resource = Fhavro.parseJsonResource(resourceName, fileContent).asInstanceOf[DomainResource]
      Fhavro.convertResourceToGenericRecord(resource, schema).asInstanceOf[GenericRecord]
    })
  }

  private def logProgress(verb: String, progress: AtomicInteger, total: Int): Unit = {
    print(s"[main] INFO ${getClass.getName} - %s%% ${verb}ed.\r".format((progress.incrementAndGet().floatValue() / total) * 100))
  }

  case class ExportResult(`type`: String, url: String)
}
