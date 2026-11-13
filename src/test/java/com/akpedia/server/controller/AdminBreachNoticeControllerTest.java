package com.akpedia.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

import com.akpedia.server.dto.BreachNoticeDetail;
import com.akpedia.server.dto.BreachNoticeRequest;
import com.akpedia.server.dto.BreachNoticeSendResult;
import com.akpedia.server.dto.BreachNoticeSummary;
import com.akpedia.server.dto.BreachNoticeTemplate;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.entity.enums.BreachNoticeRecipientStatus;
import com.akpedia.server.exception.AnpdNotInformedException;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.BreachNoticeConflictException;
import com.akpedia.server.exception.BreachNoticeNotFoundException;
import com.akpedia.server.exception.InvalidBreachNoticeException;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.BreachNoticeService;

/** Pins the routes and JSON of the breach notice screen. Who may call them is in SecurityConfigTest. */
@WebMvcTest(controllers = AdminBreachNoticeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class AdminBreachNoticeControllerTest {

    private static final long ADMIN_ID = 1L;
    private static final OffsetDateTime SENT_AT = OffsetDateTime.of(2026, 11, 12, 9, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private BreachNoticeService breachNotices;

    @MockBean
    private CurrentUser currentUser;

    @BeforeEach
    void logIn() {
        given(currentUser.id()).willReturn(ADMIN_ID);
    }

    @Test
    @DisplayName("GET /template answers the subject, the text and each field with what goes in it")
    void template() throws Exception {
        given(breachNotices.template()).willReturn(new BreachNoticeTemplate("Assunto", "Olá, {{nome_titular}}.",
                List.of(new BreachNoticeTemplate.Field("nome_titular", true, true, "Nome do funcionário", "Ana Silva"),
                        new BreachNoticeTemplate.Field("motivo_atraso", false, false, "Justificativa", "Extensão"))));

        mvc.perform(get("/api/v1/admin/breach-notices/template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Assunto"))
                .andExpect(jsonPath("$.body").value("Olá, {{nome_titular}}."))
                .andExpect(jsonPath("$.fields[0].name").value("nome_titular"))
                .andExpect(jsonPath("$.fields[0].required").value(true))
                .andExpect(jsonPath("$.fields[0].automatic").value(true))
                .andExpect(jsonPath("$.fields[0].description").value("Nome do funcionário"))
                .andExpect(jsonPath("$.fields[0].example").value("Ana Silva"))
                .andExpect(jsonPath("$.fields[1].required").value(false));
    }

    @Test
    @DisplayName("POST /preview answers the filled subject and text, 200, as the admin's own e-mail")
    void preview() throws Exception {
        BreachNoticeRequest expected = new BreachNoticeRequest(
                "Assunto", "Olá, {{nome_titular}}. Em {{data_descoberta}}", java.util.Map.of("data_descoberta", "10/11/2026"),
                null, null, null);
        given(breachNotices.preview(eq(expected), eq(ADMIN_ID)))
                .willReturn(new com.akpedia.server.dto.BreachNoticePreview("Assunto", "Olá, Admin. Em 10/11/2026"));

        mvc.perform(post("/api/v1/admin/breach-notices/preview").contentType(MediaType.APPLICATION_JSON).content("""
                        {"subject": "Assunto", "body": "Olá, {{nome_titular}}. Em {{data_descoberta}}",
                         "fields": {"data_descoberta": "10/11/2026"}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Assunto"))
                .andExpect(jsonPath("$.body").value("Olá, Admin. Em 10/11/2026"));
    }

    @Test
    @DisplayName("POST /preview of an invalid notice is 400 invalid_breach_notice")
    void previewInvalidIs400() throws Exception {
        given(breachNotices.preview(any(), eq(ADMIN_ID)))
                .willThrow(new InvalidBreachNoticeException("Campos obrigatorios em branco: data_descoberta."));

        mvc.perform(post("/api/v1/admin/breach-notices/preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_breach_notice"));
    }

    @Test
    @DisplayName("POST reads snake_case, answers 201 with the counts and the failures")
    void sendReadsSnakeCase() throws Exception {
        BreachNoticeRequest expected = new BreachNoticeRequest(
                "Assunto", "Em {{data_descoberta}}", java.util.Map.of("data_descoberta", "10/11/2026"),
                null, List.of(10L, 11L), true);
        given(breachNotices.send(eq(expected), eq(ADMIN_ID))).willReturn(new BreachNoticeSendResult(5L, 2, 1, 1,
                List.of(new BreachNoticeSendResult.Failure(11L, "b@x.com", "REJECTED", "REJECTED: 550"))));

        mvc.perform(post("/api/v1/admin/breach-notices").contentType(MediaType.APPLICATION_JSON).content("""
                        {"subject": "Assunto", "body": "Em {{data_descoberta}}", "fields": {"data_descoberta": "10/11/2026"},
                         "user_ids": [10, 11], "anpd_informed": true}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.sent").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.failures[0].user_id").value(11))
                .andExpect(jsonPath("$.failures[0].email").value("b@x.com"))
                .andExpect(jsonPath("$.failures[0].reason").value("REJECTED"))
                .andExpect(jsonPath("$.failures[0].error").value("REJECTED: 550"));
    }

    @Test
    @DisplayName("POST also takes the camelCase spelling of the ticket")
    void sendAlsoReadsCamelCase() throws Exception {
        given(breachNotices.send(any(), eq(ADMIN_ID))).willReturn(new BreachNoticeSendResult(5L, 1, 1, 0, List.of()));

        mvc.perform(post("/api/v1/admin/breach-notices").contentType(MediaType.APPLICATION_JSON).content("""
                        {"subject": "Assunto", "body": "Texto", "allUsers": true, "anpdInformed": true}
                        """))
                .andExpect(status().isCreated());

        then(breachNotices).should().send(new BreachNoticeRequest("Assunto", "Texto", null, true, null, true), ADMIN_ID);
    }

    @Test
    @DisplayName("without ANPD informed, 422 anpd_not_informed")
    void anpdNotInformedIs422() throws Exception {
        given(breachNotices.send(any(), eq(ADMIN_ID))).willThrow(new AnpdNotInformedException());

        mvc.perform(post("/api/v1/admin/breach-notices").contentType(MediaType.APPLICATION_JSON).content("""
                        {"subject": "Assunto", "body": "Texto", "all_users": true}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("anpd_not_informed"));
    }

    @Test
    @DisplayName("a send that would repeat or overlap another is 409 with its own code")
    void conflictIs409() throws Exception {
        given(breachNotices.send(any(), eq(ADMIN_ID))).willThrow(BreachNoticeConflictException.inProgress());

        mvc.perform(post("/api/v1/admin/breach-notices").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("breach_notice_in_progress"));
    }

    @Test
    @DisplayName("an invalid notice is 400 invalid_breach_notice")
    void invalidNoticeIs400() throws Exception {
        given(breachNotices.send(any(), eq(ADMIN_ID))).willThrow(new InvalidBreachNoticeException("body e obrigatorio."));

        mvc.perform(post("/api/v1/admin/breach-notices").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_breach_notice"));
    }

    @Test
    @DisplayName("GET lists the notices as a page, with the counts")
    void list() throws Exception {
        given(breachNotices.list(null, null)).willReturn(new PageResponse<>(
                List.of(new BreachNoticeSummary(5L, "Assunto", "Admin", true, SENT_AT, 2, 1, 1, 0)), 0, 20, 1, 1));

        mvc.perform(get("/api/v1/admin/breach-notices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(5))
                .andExpect(jsonPath("$.content[0].sent_by_name").value("Admin"))
                .andExpect(jsonPath("$.content[0].anpd_informed").value(true))
                .andExpect(jsonPath("$.content[0].sent_at").value("2026-11-12T09:00:00Z"))
                .andExpect(jsonPath("$.content[0].total").value(2))
                .andExpect(jsonPath("$.content[0].failed").value(1))
                .andExpect(jsonPath("$.total_elements").value(1));
    }

    @Test
    @DisplayName("GET /{id} answers the notice with each recipient")
    void detail() throws Exception {
        given(breachNotices.get(5L)).willReturn(new BreachNoticeDetail(5L, "Assunto", "Texto", "Admin", true, SENT_AT,
                1, 0, 1, 0, List.of(new BreachNoticeDetail.Recipient(
                        11L, "Bruno", "b@x.com", BreachNoticeRecipientStatus.FAILED, "REJECTED: 550"))));

        mvc.perform(get("/api/v1/admin/breach-notices/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Texto"))
                .andExpect(jsonPath("$.recipients[0].user_id").value(11))
                .andExpect(jsonPath("$.recipients[0].status").value("FAILED"))
                .andExpect(jsonPath("$.recipients[0].error").value("REJECTED: 550"));
    }

    @Test
    @DisplayName("GET /{id} of an unknown notice is 404 breach_notice_not_found")
    void unknownNoticeIs404() throws Exception {
        given(breachNotices.get(9L)).willThrow(new BreachNoticeNotFoundException(9L));

        mvc.perform(get("/api/v1/admin/breach-notices/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("breach_notice_not_found"));
    }
}
