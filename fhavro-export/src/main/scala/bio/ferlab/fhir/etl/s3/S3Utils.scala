package bio.ferlab.fhir.etl.s3

import bio.ferlab.fhir.etl.config.{AWSConfig, FhirRequest}
import bio.ferlab.fhir.etl.model.FhirBulkResource
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.services.s3.model.{HeadObjectRequest, NoSuchKeyException, PutObjectRequest}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.io.File
import java.net.URI

object S3Utils {

  def buildS3Client(configuration: AWSConfig): S3Client = {
    val accessKey = sys.env.getOrElse("AWS_ACCESS_KEY", throw new RuntimeException("Please provide the AWS_ACCESS_KEY environment variable"))
    val secretKey = sys.env.getOrElse("AWS_SECRET_KEY", throw new RuntimeException("Please provide the AWS_SECRET_KEY environment variable"))

    S3Client.builder()
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
      .endpointOverride(URI.create(configuration.endpoint))
      .serviceConfiguration(S3Configuration.builder()
        .pathStyleAccessEnabled(configuration.pathStyleAccess)
        .build())
      .httpClient(ApacheHttpClient.create())
      .build()
  }

  def writeFile(bucket: String, key: String, file: File)(implicit s3Client: S3Client): Unit = {
    val objectRequest = PutObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .build()
    s3Client.putObject(objectRequest, RequestBody.fromFile(file))
  }

  def exists(bucket: String, key: String)(implicit s3Client: S3Client): Boolean = {
    try {
      s3Client.headObject(HeadObjectRequest.builder.bucket(bucket).key(key).build)
      true
    } catch {
      case _: NoSuchKeyException =>
        false
    }
  }

  def buildKey(fhirRequest: FhirRequest): String = {
    s"raw/fhir/${fhirRequest.`type`.toLowerCase()}/study=${fhirRequest.tag}/${fhirRequest.schema}.avro"
  }

  def buildKey(resource: FhirBulkResource): String = {
    s"raw/fhir/${resource.name.toLowerCase()}/study=${resource.tag}/${resource.schema}.avro"
  }
}
