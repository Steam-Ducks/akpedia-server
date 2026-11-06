package com.akpedia.server.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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

import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsAcceptanceResponse;
import com.akpedia.server.dto.TermsStatusResponse;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.TermsChangedException;
import com.akpedia.server.security.CurrentUser;
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
    private CurrentUser currentUser;

    @BeforeEach
    void logIn() {
        given(currentUser.id()).willReturn(USER_ID);
        given(currentUser.keycloakId()).willReturn(KEYCLOAK_ID);
    }

    private static TermsVersionResponse version(int number) {
        return new TermsVersionResponse(number, "Termos de uso", "Paragrafo 1\n\nParagrafo 2", PUBLISHED_AT, "Admin");
    }

    @Test
    @DisplayName("GET /terms answers the current terms and the user's last acceptance, in snake_case")
    void statusAnswersCurrentAndAccepted() throws Exception {
        given(terms.status(KEYCLOAK_ID)).willReturn(new TermsStatusResponse(
                version(2), new TermsAcceptanceResponse(1, PUBLISHED_AT)));

        mvc.perform(get("/api/v1/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value(2))
                .andExpect(jsonPath("$.current.title").value("Termos de uso"))
                .andExpect(jsonPath("$.current.content").value("Paragrafo 1\n\nParagrafo 2"))
                .andExpect(jsonPath("$.current.published_at").exists())
                .andExpect(jsonPath("$.current.published_by").value("Admin"))
                .andExpect(jsonPath("$.accepted.version").value(1))
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
        given(terms.accept(USER_ID, 2)).willReturn(new TermsAcceptanceResponse(2, PUBLISHED_AT));

        mvc.perform(post("/api/v1/terms/accept").contentType(MediaType.APPLICATION_JSON).content("{\"version\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.accepted_at").exists());
    }

    @Test
    @DisplayName("POST /terms/accept answers 409 terms_changed for a version that is not current")
    void acceptOldVersionIsConflict() throws Exception {
        given(terms.accept(USER_ID, 1)).willThrow(new TermsChangedException(1, 2));

        mvc.perform(post("/api/v1/terms/accept").contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("terms_changed"));
    }

    @Test
    @DisplayName("POST /terms/accept without a version is a 400")
    void acceptWithoutVersion() throws Exception {
        mvc.perform(post("/api/v1/terms/accept").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        verify(terms, never()).accept(anyLong(), anyInt());
    }

    @Test
    @DisplayName("GET /admin/terms lists every version, newest first")
    void historyListsVersions() throws Exception {
        given(terms.history()).willReturn(List.of(version(2), version(1)));

        mvc.perform(get("/api/v1/admin/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));
    }

    @Test
    @DisplayName("POST /admin/terms publishes as the logged-in admin and answers 201")
    void publishAnswersCreated() throws Exception {
        given(terms.publish(USER_ID, new PublishTermsRequest("Termos de uso", "Texto"))).willReturn(version(3));

        mvc.perform(post("/api/v1/admin/terms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Termos de uso\", \"content\": \"Texto\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    @DisplayName("POST /admin/terms refuses a blank title or content")
    void publishValidates() throws Exception {
        mvc.perform(post("/api/v1/admin/terms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \" \", \"content\": \"Texto\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/terms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Termos\", \"content\": \"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/terms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"" + "x".repeat(201) + "\", \"content\": \"Texto\"}"))
                .andExpect(status().isBadRequest());
        verify(terms, never()).publish(anyLong(), any());
    }
}
