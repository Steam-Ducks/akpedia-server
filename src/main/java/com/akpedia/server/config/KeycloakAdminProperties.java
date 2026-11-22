package com.akpedia.server.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * How this server reaches Keycloak's Admin API, for the admin's users screen: through the
 * {@code akpedia-server} client's service account, which has {@code view-users} and
 * {@code manage-users}.
 *
 * @param baseUrl        Keycloak's URL as seen from this server, with no trailing slash; not the
 *                       public one inside a container
 * @param realm          the realm whose users are managed
 * @param clientId       the confidential client whose service account is used
 * @param clientSecret   its secret
 * @param connectTimeout how long to wait for the TCP connection to open
 * @param readTimeout    how long to wait for an answer
 */
@Validated
@ConfigurationProperties(prefix = "akpedia.keycloak.admin")
public record KeycloakAdminProperties(
        @DefaultValue("http://localhost/auth") @NotBlank String baseUrl,
        @DefaultValue("akpedia") @NotBlank String realm,
        @DefaultValue("akpedia-server") @NotBlank String clientId,
        @DefaultValue("akpedia-dev-server-secret") @NotBlank String clientSecret,
        @DefaultValue("5s") @NotNull Duration connectTimeout,
        @DefaultValue("10s") @NotNull Duration readTimeout) {
}
