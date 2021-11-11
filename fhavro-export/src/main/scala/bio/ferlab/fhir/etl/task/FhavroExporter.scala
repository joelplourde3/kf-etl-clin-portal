package bio.ferlab.fhir.etl.task

import bio.ferlab.fhir.Fhavro
import bio.ferlab.fhir.etl.config.{Config, FhirRequest}
import bio.ferlab.fhir.etl.fhir.FhirUtils.buildFhirClient
import bio.ferlab.fhir.etl.keycloak.Authentication.buildKeycloakAuthentication
import bio.ferlab.fhir.etl.keycloak.KeyCloak
import bio.ferlab.fhir.etl.logging.LoggerUtils
import bio.ferlab.fhir.etl.s3.S3Utils.{buildKey, buildS3Client, writeFile}
import bio.ferlab.fhir.schema.repository.SchemaMode
import ca.uhn.fhir.rest.client.impl.GenericClient
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.hl7.fhir.r4.model.{Bundle, DomainResource}
import org.slf4j.{Logger, LoggerFactory}
import software.amazon.awssdk.services.s3.S3Client

import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

class FhavroExporter(config: Config) {

  val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  implicit val keyCloak: KeyCloak = buildKeycloakAuthentication(config.keycloakConfig)

  implicit val s3Client: S3Client = buildS3Client(config.awsConfig)

  implicit val fhirClient: GenericClient = buildFhirClient(config);

  def requestExportFor(request: FhirRequest): List[DomainResource] = {
    LOGGER.info(s"Requesting Export for ${request.`type`}")
    val resources: ListBuffer[DomainResource] = new ListBuffer[DomainResource]()

    var bundle = fhirClient.search()
      .forResource(request.`type`)
      .withTag(null, request.tag)
      .returnBundle(classOf[Bundle])
      .execute()
    resources.addAll(getResourcesFromBundle(bundle))

    while (bundle.getLink("next") != null) {
      LoggerUtils.logProgress("export", resources.length)
      bundle = fhirClient.loadPage().next(bundle).execute()
      resources.addAll(getResourcesFromBundle(bundle))
    }
    resources.toList
  }

  def uploadFiles(fhirRequest: FhirRequest, schemaPath: String, resources: List[DomainResource]): Unit = {
    LOGGER.info(s"Converting resource(s): ${fhirRequest.`type`}")
    val key = buildKey(fhirRequest)
    val file = convertResources(fhirRequest, schemaPath, resources)
    writeFile(config.awsConfig.bucketName, key, file)
    LOGGER.info(s"Uploaded ${fhirRequest.schema} successfully!")
  }

  private def convertResources(fhirRequest: FhirRequest, schemaPath: String, resources: List[DomainResource]): File = {
    val resourceName = fhirRequest.`type`.toLowerCase

    val schemaRelativePath = s"$schemaPath/${fhirRequest.schema}"

    LOGGER.info(s"--- Loading schema: ${fhirRequest.schema} from ./$schemaRelativePath")
    val schema = Fhavro.loadSchema(schemaRelativePath, SchemaMode.ADVANCED)

    LOGGER.info(s"--- Converting $resourceName to GenericRecord(s)")
    val genericRecords: List[GenericRecord] = convertFileContentToGenericRecord(schema, resources)

    LOGGER.info(s"--- Serializing Generic Record(s) for $resourceName")
    Files.createDirectories(Paths.get("./tmp"))
    val file = new File(s"./tmp/$resourceName.avro")
    val fileOutputStream = new FileOutputStream(file)
    Fhavro.serializeGenericRecords(schema, genericRecords.asJava, fileOutputStream)
    fileOutputStream.close()
    file
  }

  private def convertFileContentToGenericRecord(schema: Schema, resources: List[DomainResource]): List[GenericRecord] = {
    val total = resources.length
    val progress = new AtomicInteger()
    resources.map(resource => {
      LoggerUtils.logProgressAtomic("convert", progress, total)
      Fhavro.convertResourceToGenericRecord(resource, schema)
    })
  }

  private def getResourcesFromBundle(bundle: Bundle): mutable.Buffer[DomainResource] = {
    bundle.getEntry
      .asScala
      .map(entry => entry.getResource.asInstanceOf[DomainResource])
  }
}
