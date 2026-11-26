package com.akpedia.server.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsAcceptanceResponse;
import com.akpedia.server.dto.TermsHistoryDetail;
import com.akpedia.server.dto.TermsHistoryEntry;
import com.akpedia.server.dto.TermsStatusResponse;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.TermsChangedException;
import com.akpedia.server.exception.TermsHistoryEntryNotFoundException;
import com.akpedia.server.exception.TermsVersionExistsException;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.security.ProvisionedUser;
import com.akpedia.server.service.TermsHistoryService;
import com.akpedia.server.service.TermsService;

@WebMvcTest(controllers = TermsController.class)
// The endpoints are under test, not who may call them: SecurityConfigTest and TermsGateFilterTest cover that.
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class TermsControllerTest {

    private static final long USER_ID = 20L;
    private static final String KEYCLOAK_ID = "3f1c2a9e-0000-4000-8000-000000000001";
    private static final OffsetDateTime PUBLISHED_AT = OffsetDateTime.parse("2026-11-06T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockBean
    private TermsService terms;

    @MockBean
    private TermsHistoryService termsHistory;

    @MockBean
    private CurrentUser currentUser;

    @BeforeEach
    void logIn() {
        given(currentUser.id()).willReturn(USER_ID);
        given(currentUser.provisioned()).willReturn(new ProvisionedUser(USER_ID, null));
        given(currentUser.keycloakId()).willReturn(KEYCLOAK_ID);
    }

    private static TermsVersionResponse version(String number) {
        return new TermsVersionResponse(number, "Termos de uso", "Paragrafo 1\n\nParagrafo 2", PUBLISHED_AT, "Admin");
    }

    @Test
    @DisplayName("GET /terms answers the current terms and the user's last acceptance, in snake_case")
    void statusAnswersCurrentAndAccepted() throws Exception {
        given(terms.status(KEYCLOAK_ID)).willReturn(new TermsStatusResponse(
                version("2.0"), new TermsAcceptanceResponse("1.0", PUBLISHED_AT)));

        mvc.perform(get("/api/v1/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value("2.0"))
                .andExpect(jsonPath("$.current.title").value("Termos de uso"))
                .andExpect(jsonPath("$.current.content").value("Paragrafo 1\n\nParagrafo 2"))
                .andExpect(jsonPath("$.current.published_at").exists())
                .andExpect(jsonPath("$.current.published_by").value("Admin"))
                .andExpect(jsonPath("$.accepted.version").value("1.0"))
                .andExpect(jsonPath("$.accepted.accepted_at").exists());
    }

    @Test
    @DisplayName("GET /terms answers null fields when nothing was published or accepted")
    void statusWithNothingPublished() throws Exception {
        given(terms.status(KEYCLOAK_ID)).willReturn(new TermsStatusResponse(null, null));

        mvc.perform(get("/api/v1/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(nullValue()))
                .andExpect(jsonPath("$.accepted").value(nullValue()));
    }

    @Test
    @DisplayName("POST /terms/accept records the acceptance for the logged-in user")
    void acceptRecordsForCurrentUser() throws Exception {
        given(terms.accept(USER_ID, "2.0", java.util.Map.of())).willReturn(new TermsAcceptanceResponse("2.0", PUBLISHED_AT));

        mvc.perform(post("/api/v1/terms/accept").contentType(MediaType.APPLICATION_JSON).content("{\"version\": \"2.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2.0"))
                .andExpect(jsonPath("$.accepted_at").exists());
    }

    @Test
    @DisplayName("POST /terms/accept answers 409 terms_changed for a version that is not current")
    void acceptOldVersionIsConflict() throws Exception {
        given(terms.accept(USER_ID, "1.0", java.util.Map.of())).willThrow(new TermsChangedException("1.0", "2.0"));

        mvc.perform(post("/api/v1/terms/accept").contentType(MediaType.APPLICATION_JSON).content("{\"version\": \"1.0\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("terms_changed"));
    }

    @Test
    @DisplayName("POST /terms/accept without a version is a 400")
    void acceptWithoutVersion() throws Exception {
        mvc.perform(post("/api/v1/terms/accept").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        verify(terms, never()).accept(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("POST /terms/decline records the refusal and answers 204")
    void declineAnswersNoContent() throws Exception {
        mvc.perform(post("/api/v1/terms/decline").contentType(MediaType.APPLICATION_JSON).content("{\"version\": \"2026.1\"}"))
                .andExpect(status().isNoContent());
        verify(terms).decline("2026.1");
    }

    @Test
    @DisplayName("POST /terms/decline without a version is a 400")
    void declineWithoutVersion() throws Exception {
        mvc.perform(post("/api/v1/terms/decline").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        verify(terms, never()).decline(anyString());
    }

    @Test
    @DisplayName("GET /admin/terms lists every version, newest first")
    void historyListsVersions() throws Exception {
        given(terms.history()).willReturn(List.of(version("10"), version("9")));

        mvc.perform(get("/api/v1/admin/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("10"))
                .andExpect(jsonPath("$[1].version").value("9"));
    }

    @Test
    @DisplayName("POST /admin/terms publishes as the logged-in admin and answers 201 with the version it got")
    void publishAnswersCreated() throws Exception {
        given(terms.publish(USER_ID, new PublishTermsRequest("Termos de uso", "Texto"))).willReturn(version("3.0"));

        mvc.perform(post("/api/v1/admin/terms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Termos de uso\", \"content\": \"Texto\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("3.0"));
    }

    @Test
    @DisplayName("POST /admin/terms refuses a blank or too long title, and a blank content")
    void publishValidates() throws Exception {
        for (String body : new String[] {
                "{\"title\": \" \", \"content\": \"Texto\"}",
                "{\"title\": \"Termos\", \"content\": \"\"}",
                "{\"title\": \"" + "x".repeat(201) + "\", \"content\": \"Texto\"}"}) {
            mvc.perform(post("/api/v1/admin/terms").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(terms, never()).publish(anyLong(), any());
    }

    @Test
    @DisplayName("POST /admin/terms answers 409 terms_version_exists when the number was taken")
    void publishRefusesRepeatedVersion() throws Exception {
        given(terms.publish(eq(USER_ID), any())).willThrow(new TermsVersionExistsException("1.0"));

        mvc.perform(post("/api/v1/admin/terms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Termos\", \"content\": \"Texto\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("terms_version_exists"))
                .andExpect(jsonPath("$.message").value("Já existe a versão 1.0. Recarregue a página e publique de novo."));
    }

    @Test
    @DisplayName("GET /admin/terms/history answers the page with each entry's action")
    void historyAnswersThePage() throws Exception {
        TermsHistoryEntry entry = new TermsHistoryEntry(TermsHistoryEntry.UPDATED, 9L, "2.0", 3L, "Ana Souza",
                "ana@akaer.com", PUBLISHED_AT);
        given(termsHistory.search("2.0", "ana", 1, 10)).willReturn(new PageResponse<>(List.of(entry), 1, 10, 11, 2));

        mvc.perform(get("/api/v1/admin/terms/history?version=2.0&user=ana&page=1&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("updated"))
                .andExpect(jsonPath("$.content[0].id").value(9))
                .andExpect(jsonPath("$.content[0].user_name").value("Ana Souza"))
                .andExpect(jsonPath("$.content[0].user_email").value("ana@akaer.com"))
                .andExpect(jsonPath("$.content[0].at").value(startsWith("2026-11-06T12:00:00")))
                .andExpect(jsonPath("$.total_pages").value(2));
    }

    @Test
    @DisplayName("GET /admin/terms/history/{action}/{id} answers the permissions before and after, or 404")
    void historyEntryAnswersThePermissions() throws Exception {
        given(termsHistory.detail("accepted", 7L)).willReturn(new TermsHistoryDetail("accepted", 7L, "2.0",
                "Ana Souza", "ana@akaer.com", PUBLISHED_AT, List.of(
                        new TermsHistoryDetail.Permission("newsletter", "Newsletter", "E-mails", true, false, false),
                        new TermsHistoryDetail.Permission("analise", "Análise", "Métricas", false, null, false))));
        given(termsHistory.detail("declined", 8L)).willThrow(new TermsHistoryEntryNotFoundException("declined", 8L));

        mvc.perform(get("/api/v1/admin/terms/history/accepted/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].granted_before").value(true))
                .andExpect(jsonPath("$.permissions[0].granted_after").value(false))
                .andExpect(jsonPath("$.permissions[1].granted_after").value(nullValue()))
                .andExpect(jsonPath("$.permissions[1].changed").value(false));
        mvc.perform(get("/api/v1/admin/terms/history/declined/8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("terms_history_entry_not_found"));
    }
}
