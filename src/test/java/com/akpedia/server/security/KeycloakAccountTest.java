package com.akpedia.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.akpedia.server.exception.IncompleteAccountException;

class KeycloakAccountTest {

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3f1c2a9e-0000-4000-8000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    @DisplayName("reads the id, email, name and sector from the token")
    void readsTheAccount() {
        KeycloakAccount account = KeycloakAccount.from(token(Map.of(
                "email", "ana@akpedia.test", "name", "Ana Souza", "sector", "Setor 1")));

        assertThat(account).isEqualTo(new KeycloakAccount(
                "3f1c2a9e-0000-4000-8000-000000000001", "ana@akpedia.test", "Ana Souza", "Setor 1"));
    }

    @Test
    @DisplayName("without a name claim, joins the first and last names")
    void joinsGivenAndFamilyNames() {
        KeycloakAccount account = KeycloakAccount.from(token(Map.of(
                "email", "ana@akpedia.test", "given_name", "Ana", "family_name", "Souza", "sector", "Setor 1")));

        assertThat(account.name()).isEqualTo("Ana Souza");
    }

    @Test
    @DisplayName("without any name, uses the email")
    void fallsBackToEmail() {
        KeycloakAccount account = KeycloakAccount.from(token(Map.of(
                "email", "ana@akpedia.test", "sector", "Setor 1")));

        assertThat(account.name()).isEqualTo("ana@akpedia.test");
    }

    @Test
    @DisplayName("refuses an account with no sector")
    void refusesMissingSector() {
        assertThatThrownBy(() -> KeycloakAccount.from(token(Map.of("email", "ana@akpedia.test"))))
                .isInstanceOf(IncompleteAccountException.class)
                .hasMessageContaining("setor");
    }

    @Test
    @DisplayName("refuses an account with no email")
    void refusesMissingEmail() {
        assertThatThrownBy(() -> KeycloakAccount.from(token(Map.of("sector", "Setor 1"))))
                .isInstanceOf(IncompleteAccountException.class)
                .hasMessageContaining("email");
    }
}
