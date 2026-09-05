package com.haloticket.user_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(
        String authServerUrl,
        String realm,
        String clientId,
        String clientSecret
) {
    public KeycloakProperties {
        if (authServerUrl == null || authServerUrl.isBlank()) {
            authServerUrl = "http://localhost:9080";
        }
        if (realm == null || realm.isBlank()) {
            realm = "haloticket-realm";
        }
        if (clientId == null || clientId.isBlank()) {
            clientId = "user-service";
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            clientSecret = "user-service-secret";
        }
    }
}
