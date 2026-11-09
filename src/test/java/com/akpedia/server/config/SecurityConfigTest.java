package com.akpedia.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.repository.UserRepository;
import com.akpedia.server.service.AccountDeletionLog;
import com.akpedia.server.service.AuditService;
import com.akpedia.server.service.TermsService;

import jakarta.servlet.http.HttpServletRequest;

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

        @GetMapping("/api/v1/terms")
        String terms() {
            return "ok";
        }

        @PostMapping("/api/v1/terms/accept")
        String acceptTerms() {
            return "ok";
        }

        @PostMapping("/api/v1/terms/decline")
        String declineTerms() {
            return "ok";
        }

        @GetMapping("/api/v1/admin/terms")
        String adminTerms() {
            return "ok";
        }

        @GetMapping("/api/v1/admin/users")
        String adminUsers() {
            return "ok";
        }

        @GetMapping("/api/v1/admin/audit")
        String adminAudit() {
            return "ok";
        }

        @GetMapping("/api/v1/me")
        String me() {
            return "ok";
        }

        @DeleteMapping("/api/v1/me")
        String deleteMe() {
            return "ok";
        }

        @GetMapping("/api/v1/me/profile")
        String profile() {
            return "ok";
        }

        @GetMapping("/api/v1/admin/profile-requests")
        String profileRequests() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mvc;

    /** Says nobody has terms to accept, unless a test says otherwise. */
    @MockBean
    private TermsService terms;

    @MockBean
    private AuditService audit;

    /** Says no account was deleted, unless a test says otherwise. */
    @MockBean
    private AccountDeletionLog deletions;

    /** Says no account was disabled, unless a test says otherwise. */
    @MockBean
    private UserRepository users;

    /** A token as Keycloak issues it, with the given realm roles in {@code realm_access.roles}. */
    private static JwtRequestPostProcessor tokenWithRoles(String... roles) {
        return jwt()
                .jwt(token -> token.claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new SecurityConfig.RealmRolesConverter());
    }

    @Test
    @DisplayName("an /api route without a token answers 401")
    void apiWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("an invalid token gets 401 in the API's error format, with WWW-Authenticate")
    void invalidTokenIsUnauthorizedAsJson() throws Exception {
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    @DisplayName("a role that does not reach the route gets 403 in the API's error format")
    void forbiddenIsJson() throws Exception {
        mvc.perform(get("/api/v1/admin/users").with(tokenWithRoles("STANDARD")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("a disabled account's token, still valid, gets 401 account_disabled")
    void disabledAccountIsRefused() throws Exception {
        given(users.existsByKeycloakIdAndActiveFalse("off")).willReturn(true);

        mvc.perform(get("/api/v1/categories").with(tokenWithRoles("STANDARD").jwt(token -> token.subject("off"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("account_disabled"));
        mvc.perform(get("/api/v1/categories").with(tokenWithRoles("STANDARD").jwt(token -> token.subject("on"))))
                .andExpect(status().isOk());
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

    @Test
    @DisplayName("only an ADMIN reaches the admin routes")
    void adminRoutesNeedAdmin() throws Exception {
        mvc.perform(get("/api/v1/admin/terms").with(tokenWithRoles("STANDARD", "APPROVER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/terms").with(tokenWithRoles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users").with(tokenWithRoles("STANDARD", "APPROVER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").with(tokenWithRoles("APPROVER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").with(tokenWithRoles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/profile-requests").with(tokenWithRoles("APPROVER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/profile-requests").with(tokenWithRoles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/profile").with(tokenWithRoles("STANDARD"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit").with(tokenWithRoles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("until the current terms are accepted, /api answers 403 terms_pending")
    void termsGateHoldsBackTheApi() throws Exception {
        given(terms.mustAccept(anyString())).willReturn(true);

        mvc.perform(get("/api/v1/categories").with(tokenWithRoles("STANDARD")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("terms_pending"));
        // Admin routes too: after publishing, the admin accepts like everyone else.
        mvc.perform(get("/api/v1/admin/terms").with(tokenWithRoles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("terms_pending"));
        // The profile too: only deleting the account is open before accepting.
        mvc.perform(get("/api/v1/me/profile").with(tokenWithRoles("STANDARD")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("terms_pending"));
    }

    @Test
    @DisplayName("the terms screen's own routes, health and /internal stay open before accepting")
    void termsGateLeavesItsOwnRoutesOpen() throws Exception {
        given(terms.mustAccept(anyString())).willReturn(true);

        mvc.perform(get("/api/v1/terms").with(tokenWithRoles("STANDARD"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").with(tokenWithRoles("STANDARD"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/terms/accept").with(tokenWithRoles("STANDARD"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/terms/decline").with(tokenWithRoles("STANDARD"))).andExpect(status().isOk());
        // A user may delete their account without accepting.
        mvc.perform(delete("/api/v1/me").with(tokenWithRoles("STANDARD"))).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/internal/documents/1/file")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a deleted account's token is refused with 401 account_deleted, except to delete it again")
    void deletedAccountIsRefused() throws Exception {
        given(deletions.isDeleted("gone")).willReturn(true);

        mvc.perform(get("/api/v1/categories").with(tokenWithRoles("STANDARD").jwt(token -> token.subject("gone"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("account_deleted"));
        mvc.perform(delete("/api/v1/me").with(tokenWithRoles("STANDARD").jwt(token -> token.subject("gone"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/categories").with(tokenWithRoles("STANDARD").jwt(token -> token.subject("here"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the terms gate comes after the login and role checks")
    void termsGateRunsAfterAuthentication() throws Exception {
        given(terms.mustAccept(anyString())).willReturn(true);

        mvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
        // Refused for the missing role, not for the terms.
        mvc.perform(get("/api/v1/documents/pending").with(tokenWithRoles("STANDARD")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    @DisplayName("every /api request is recorded with its final status, a 401 included")
    void requestsAreAudited() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
        verify(audit).recordRequest(any(HttpServletRequest.class), eq(401), anyLong());

        mvc.perform(get("/api/v1/documents/pending").with(tokenWithRoles("STANDARD")))
                .andExpect(status().isForbidden());
        verify(audit).recordRequest(any(HttpServletRequest.class), eq(403), anyLong());
    }

    @Test
    @DisplayName("the request is recorded while the token's user is still known")
    void requestAuditSeesTheUser() throws Exception {
        List<Authentication> seen = new ArrayList<>();
        willAnswer(invocation -> seen.add(SecurityContextHolder.getContext().getAuthentication()))
                .given(audit).recordRequest(any(), eq(200), anyLong());

        mvc.perform(get("/api/v1/categories").with(tokenWithRoles("STANDARD"))).andExpect(status().isOk());

        assertThat(seen).singleElement().satisfies(authentication -> assertThat(authentication).isNotNull());
    }

    @Test
    @DisplayName("health, /internal and reading the audit itself are not recorded")
    void excludedRoutesAreNotAudited() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/internal/documents/1/file")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit").with(tokenWithRoles("ADMIN"))).andExpect(status().isOk());

        verify(audit, never()).recordRequest(any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("a failure to record never reaches the response")
    void auditFailureIsSwallowed() throws Exception {
        willAnswer(invocation -> {
            throw new IllegalStateException("database down");
        }).given(audit).recordRequest(any(), anyInt(), anyLong());

        mvc.perform(get("/api/v1/categories").with(tokenWithRoles("STANDARD"))).andExpect(status().isOk());
    }
}
