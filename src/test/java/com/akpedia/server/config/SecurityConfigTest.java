package com.akpedia.server.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who may call which route. The routes are stand-ins with the real paths, so what is under test is
 * {@link SecurityConfig} alone, not the controllers behind it.
 */
@WebMvcTest(controllers = SecurityConfigTest.Routes.class)
@Import({SecurityConfig.class, SecurityConfigTest.Routes.class})
class SecurityConfigTest {

    @RestController
    static class Routes {

        @GetMapping("/health")
        String health() {
            return "ok";
        }

        @GetMapping("/internal/documents/1/file")
        String internal() {
            return "ok";
        }

        @GetMapping("/api/v1/categories")
        String categories() {
            return "ok";
        }

        @GetMapping("/api/v1/documents/pending")
        String pending() {
            return "ok";
        }

        @PostMapping("/api/v1/documents/1/approve")
        String approve() {
            return "ok";
        }

        @PostMapping("/api/v1/documents/1/reject")
        String reject() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mvc;

    /** A token as Keycloak issues it, with the given realm roles in {@code realm_access.roles}. */
    private static JwtRequestPostProcessor tokenWithRoles(String... roles) {
        return jwt()
                .jwt(token -> token.claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new SecurityConfig.RealmRolesConverter());
    }

    @Test
    @DisplayName("an /api route without a token answers 401")
    void apiWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an /api route answers any logged-in user")
    void apiWithTokenIsAllowed() throws Exception {
        mvc.perform(get("/api/v1/categories").with(tokenWithRoles("STANDARD"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("health and the worker's /internal routes need no user token")
    void publicRoutesNeedNoToken() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        // Guarded by InternalTokenFilter instead, with the worker's shared token.
        mvc.perform(get("/internal/documents/1/file")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a STANDARD user cannot see the review queue, approve or return")
    void standardUserCannotReview() throws Exception {
        mvc.perform(get("/api/v1/documents/pending").with(tokenWithRoles("STANDARD")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/documents/1/approve").with(tokenWithRoles("STANDARD")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/documents/1/reject").with(tokenWithRoles("STANDARD")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an APPROVER sees the review queue, approves and returns")
    void approverCanReview() throws Exception {
        mvc.perform(get("/api/v1/documents/pending").with(tokenWithRoles("STANDARD", "APPROVER")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/documents/1/approve").with(tokenWithRoles("APPROVER")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/documents/1/reject").with(tokenWithRoles("APPROVER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an ADMIN reviews too")
    void adminCanReview() throws Exception {
        mvc.perform(get("/api/v1/documents/pending").with(tokenWithRoles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/documents/1/approve").with(tokenWithRoles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a token without realm_access has no roles, so it cannot review")
    void tokenWithoutRealmAccessHasNoRoles() throws Exception {
        mvc.perform(get("/api/v1/documents/pending")
                        .with(jwt().authorities(new SecurityConfig.RealmRolesConverter())))
                .andExpect(status().isForbidden());
    }
}
