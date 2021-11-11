package bio.ferlab.fhir.etl.keycloak

import bio.ferlab.fhir.etl.config.KeycloakConfig

object Authentication {

  def buildKeycloakAuthentication(keycloakConfig: KeycloakConfig): KeyCloak = {
    new KeyCloak(keycloakConfig)
  }
}

class KeyCloak(keycloakConfig: KeycloakConfig) {

  def getAuthentication: String = {
    keycloakConfig.cookie
  }
}
