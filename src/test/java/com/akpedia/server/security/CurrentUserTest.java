package com.akpedia.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class CurrentUserTest {

    @Mock
    private UserProvisioningService provisioning;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void logIn() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3f1c2a9e-0000-4000-8000-000000000001")
                .claim("email", "ana@akpedia.test")
                .claim("name", "Ana")
                .claim("sector", "Engenharia")
                .claim("cpf", "52998224725")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    @DisplayName("answers the id of the logged-in user's row")
    void answersTheUsersId() {
        logIn();
        given(provisioning.provision(any())).willReturn(7L);

        assertThat(new CurrentUser(provisioning).id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("when a concurrent request created the row first, finds it on a second try")
    void retriesAfterConcurrentCreation() {
        logIn();
        given(provisioning.provision(any()))
                .willThrow(new DataIntegrityViolationException("uq_users_keycloak_id"))
                .willReturn(7L);

        assertThat(new CurrentUser(provisioning).id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("fails when the request carries no token")
    void failsWithoutToken() {
        assertThatThrownBy(() -> new CurrentUser(provisioning).id()).isInstanceOf(IllegalStateException.class);
    }
}
