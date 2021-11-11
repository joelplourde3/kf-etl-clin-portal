package bio.ferlab.fhir.etl.request

import bio.ferlab.fhir.etl.config.FhirRequest
import sttp.model.Uri

object RequestUtils {

  def buildAsyncUri(fhirBaseUrl: String, fhirRequest: FhirRequest): Uri = {
    uri"$fhirBaseUrl/$$export?${toAsyncQueryParams(fhirRequest)}"
  }

  private def toAsyncQueryParams(fhirRequest: FhirRequest): Map[String, Any] = {
    Map(
      "_type" -> fhirRequest.`type`,
      "_tag" -> fhirRequest.tag,
      "_total" -> fhirRequest.total,
      "_typeFilter" -> fhirRequest.filter,
      "_count" -> fhirRequest.count
    )
  }
}
