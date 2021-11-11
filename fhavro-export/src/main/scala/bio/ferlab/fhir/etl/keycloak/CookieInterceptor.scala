package bio.ferlab.fhir.etl.keycloak

import ca.uhn.fhir.rest.client.api.{IClientInterceptor, IHttpRequest, IHttpResponse}

class CookieInterceptor(cookie: String) extends IClientInterceptor {

  override def interceptRequest(theRequest: IHttpRequest): Unit = {
    theRequest.addHeader("Cookie", cookie)
  }

  override def interceptResponse(theResponse: IHttpResponse): Unit = {}
}
