package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.akpedia.server.dto.AuditEntry;
import com.akpedia.server.dto.AuditKind;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.repository.AuditLogRepository;
import com.akpedia.server.security.RequestAuditFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What an audit entry records about who, from where and what, against the real schema; and the
 * request filter writing one for real.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditService.class, AccountDeletionLog.class})
class AuditServiceTest {

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditLogRepository logs;

    @Autowired
    private TestEntityManager entityManager;

    private User ana;
    private String email;

    @BeforeEach
    void setUp() {
        email = "ana." + UUID.randomUUID() + "@akpedia.test";
        ana = new User("Ana Souza", email, entityManager.persist(new Sector("Setor-" + UUID.randomUUID(), null)));
        ana.setKeycloakId(UUID.randomUUID().toString());
        entityManager.persist(ana);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void logInAs(String keycloakId, String tokenEmail) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(keycloakId).claim("email", tokenEmail).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("User-Agent", "Mozilla/5.0 (teste)");
        return request;
    }

    private static JsonNode json(String text) throws JsonProcessingException {
        return new ObjectMapper().readTree(text);
    }

    private AuditLog onlyEntryOf(String action) {
        entityManager.flush();
        entityManager.clear();
        List<AuditLog> found = logs.findAll().stream()
                .filter(log -> email.equals(log.getUserEmail()) && action.equals(log.getAction()))
                .toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    @DisplayName("an event records the token's user, their row, the request and the details")
    void eventRecordsWhoWhereAndWhat() throws Exception {
        logInAs(ana.getKeycloakId(), email);
        MockHttpServletRequest request = request("POST", "/api/v1/terms/accept");
        request.addHeader("X-Forwarded-For", "200.1.2.3, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        audit.record(null, AuditService.TERMS_ACCEPTED, AuditService.TERMS_VERSION, 4L,
                AuditService.details("version", 2, "comment", null));

        AuditLog entry = onlyEntryOf(AuditService.TERMS_ACCEPTED);
        assertThat(entry.getUser().getId()).isEqualTo(ana.getId());
        assertThat(entry.getUserKeycloakId()).isEqualTo(ana.getKeycloakId());
        assertThat(entry.getEntityType()).isEqualTo("TERMS_VERSION");
        assertThat(entry.getEntityId()).isEqualTo(4L);
        assertThat(json(entry.getDetails())).isEqualTo(json("{\"version\": 2}"));
        assertThat(entry.getIpAddress()).isEqualTo("200.1.2.3");
        assertThat(entry.getUserAgent()).isEqualTo("Mozilla/5.0 (teste)");
        assertThat(entry.getHttpMethod()).isEqualTo("POST");
        assertThat(entry.getPath()).isEqualTo("/api/v1/terms/accept");
        assertThat(entry.getStatusCode()).isNull();
    }

    @Test
    @DisplayName("a user without a row yet is still recorded by Keycloak id and email, and no row is created")
    void userWithoutRow() {
        String keycloakId = UUID.randomUUID().toString();
        logInAs(keycloakId, email);

        audit.record(null, AuditService.TERMS_DECLINED, AuditService.TERMS_VERSION, null, AuditService.details());

        AuditLog entry = onlyEntryOf(AuditService.TERMS_DECLINED);
        assertThat(entry.getUser()).isNull();
        assertThat(entry.getUserKeycloakId()).isEqualTo(keycloakId);
        assertThat(entry.getDetails()).isNull();
        // Outside a request, there is nothing to say about one.
        assertThat(entry.getPath()).isNull();
    }

    @Test
    @DisplayName("the request filter records the final status, the user and the query string, never the body")
    void filterWritesTheRequest() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/v1/documents");
        request.setQueryString("page=0&size=20");
        request.setContent("segredo".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestAuditFilter(audit).doFilter(request, response, (req, res) -> {
            // What the bearer token filter does further in the chain.
            logInAs(ana.getKeycloakId(), email);
            ((MockHttpServletResponse) res).setStatus(403);
        });

        AuditLog entry = onlyEntryOf(AuditLog.HTTP_REQUEST);
        assertThat(entry.getStatusCode()).isEqualTo(403);
        assertThat(entry.getUser().getId()).isEqualTo(ana.getId());
        assertThat(entry.getHttpMethod()).isEqualTo("GET");
        assertThat(entry.getPath()).isEqualTo("/api/v1/documents");
        assertThat(entry.getIpAddress()).isEqualTo("10.0.0.9");
        assertThat(entry.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(json(entry.getDetails())).isEqualTo(json("{\"query\": \"page=0&size=20\"}"));
    }

    @Test
    @DisplayName("the search maps each entry with its details as JSON and the user's name")
    void searchMapsEntries() {
        logInAs(ana.getKeycloakId(), email);
        audit.record(ana.getId(), AuditService.DOCUMENT_APPROVED, AuditService.DOCUMENT, 7L,
                AuditService.details("category_id", 3));
        entityManager.flush();
        entityManager.clear();

        PageResponse<AuditEntry> page = audit.search(AuditKind.EVENT, null, email, null, null, null, null);

        assertThat(page.content()).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("DOCUMENT_APPROVED");
            assertThat(entry.userName()).isEqualTo("Ana Souza");
            assertThat(entry.userPseudonym()).isNull();
            assertThat(entry.details().get("category_id").asInt()).isEqualTo(3);
        });
        assertThat(page.size()).isEqualTo(50);
        assertThat(audit.eventActions()).contains("DOCUMENT_APPROVED").doesNotContain(AuditLog.HTTP_REQUEST).isSorted();
    }

    @Test
    @DisplayName("a negative page or a size out of range is refused")
    void searchValidatesThePage() {
        assertThatThrownBy(() -> audit.search(null, null, null, null, null, -1, null))
                .isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> audit.search(null, null, null, null, null, 0, 201))
                .isInstanceOf(InvalidPageRequestException.class);
    }
}
