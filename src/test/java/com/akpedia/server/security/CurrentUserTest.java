package com.akpedia.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.akpedia.server.exception.AccountCpfException;

@ExtendWith(MockitoExtension.class)
class CurrentUserTest {

    @Mock
    private UserProvisioningService provisioning;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void logIn(String... roles) {
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
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    @Test
    @DisplayName("answers the id of the logged-in user's row")
    void answersTheUsersId() {
        logIn();
        given(provisioning.provision(any())).willReturn(new ProvisionedUser(7L, null));

        assertThat(new CurrentUser(provisioning).id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("when a concurrent request created the row first, finds it on a second try")
    void retriesAfterConcurrentCreation() {
        logIn();
        given(provisioning.provision(any()))
                .willThrow(new DataIntegrityViolationException("uq_users_keycloak_id"))
                .willReturn(new ProvisionedUser(7L, null));

        assertThat(new CurrentUser(provisioning).id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("a refused CPF fails id() with its code, but provisioned() still answers the row")
    void refusedCpf() {
        logIn();
        given(provisioning.provision(any())).willReturn(new ProvisionedUser(7L, CpfProblem.TAKEN));
        CurrentUser currentUser = new CurrentUser(provisioning);

        assertThatThrownBy(currentUser::id).isInstanceOf(AccountCpfException.class)
                .extracting("code").isEqualTo("cpf_taken");
        assertThat(currentUser.provisioned()).isEqualTo(new ProvisionedUser(7L, CpfProblem.TAKEN));
    }

    @Test
    @DisplayName("fails when the request carries no token")
    void failsWithoutToken() {
        assertThatThrownBy(() -> new CurrentUser(provisioning).id()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the viewer is the user's row, flagged as a reviewer for APPROVER or ADMIN only")
    void viewerCarriesTheReviewerFlag() {
        given(provisioning.provision(any())).willReturn(new ProvisionedUser(7L, null));
        CurrentUser currentUser = new CurrentUser(provisioning);

        logIn("STANDARD");
        assertThat(currentUser.viewer()).isEqualTo(new Viewer(7L, false));
        logIn("STANDARD", "APPROVER");
        assertThat(currentUser.viewer()).isEqualTo(new Viewer(7L, true));
        logIn("ADMIN");
        assertThat(currentUser.viewer()).isEqualTo(new Viewer(7L, true));
    }

    @Test
    @DisplayName("a refused CPF fails viewer() the way it fails id()")
    void viewerRefusesAnAccountWithARefusedCpf() {
        logIn("APPROVER");
        given(provisioning.provision(any())).willReturn(new ProvisionedUser(7L, CpfProblem.INVALID));

        assertThatThrownBy(new CurrentUser(provisioning)::viewer).isInstanceOf(AccountCpfException.class);
    }
}
