package com.akpedia.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.dto.AuditEntry;
import com.akpedia.server.dto.AuditKind;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AuditController.class)
// The endpoints are under test, not who may call them: SecurityConfigTest covers that.
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class AuditControllerTest {

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-11-07T23:59:59-03:00");

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AuditService audit;

    @Test
    @DisplayName("GET /admin/audit passes every filter and answers the entries in snake_case")
    void searchAnswersEntries() throws Exception {
        AuditEntry entry = new AuditEntry(9L, FROM, "DOCUMENT_APPROVED", "ana@akpedia.test", "Ana Souza", null, "DOCUMENT", 7L,
                new ObjectMapper().readTree("{\"category_id\": 3}"), "10.0.0.9", "Mozilla", "POST",
                "/api/v1/documents/7/approve", null, null);
        given(audit.search(AuditKind.ALL, "DOCUMENT_APPROVED", "ana", FROM, TO, 1, 10))
                .willReturn(new PageResponse<>(List.of(entry), 1, 10, 11, 2));

        mvc.perform(get("/api/v1/admin/audit")
                        .param("kind", "ALL").param("action", "DOCUMENT_APPROVED").param("user", "ana")
                        .param("from", "2026-11-01T00:00:00Z").param("to", "2026-11-07T23:59:59-03:00")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(9))
                .andExpect(jsonPath("$.content[0].created_at").exists())
                .andExpect(jsonPath("$.content[0].user_email").value("ana@akpedia.test"))
                .andExpect(jsonPath("$.content[0].user_name").value("Ana Souza"))
                .andExpect(jsonPath("$.content[0].user_pseudonym").isEmpty())
                .andExpect(jsonPath("$.content[0].entity_type").value("DOCUMENT"))
                .andExpect(jsonPath("$.content[0].entity_id").value(7))
                .andExpect(jsonPath("$.content[0].details.category_id").value(3))
                .andExpect(jsonPath("$.content[0].ip_address").value("10.0.0.9"))
                .andExpect(jsonPath("$.content[0].user_agent").value("Mozilla"))
                .andExpect(jsonPath("$.content[0].http_method").value("POST"))
                .andExpect(jsonPath("$.content[0].path").value("/api/v1/documents/7/approve"))
                .andExpect(jsonPath("$.content[0].status_code").isEmpty())
                .andExpect(jsonPath("$.total_pages").value(2));
    }

    @Test
    @DisplayName("GET /admin/audit refuses an unknown kind, a malformed date and a page out of range")
    void searchRefusesBadFilters() throws Exception {
        given(audit.search(any(), any(), any(), any(), any(), any(), any()))
                .willThrow(new InvalidPageRequestException(200));

        mvc.perform(get("/api/v1/admin/audit").param("kind", "SOMETHING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(get("/api/v1/admin/audit").param("from", "ontem"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(get("/api/v1/admin/audit").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    @DisplayName("GET /admin/audit/actions answers the event names")
    void actions() throws Exception {
        given(audit.eventActions()).willReturn(List.of("DOCUMENT_APPROVED", "TERMS_ACCEPTED"));

        mvc.perform(get("/api/v1/admin/audit/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("DOCUMENT_APPROVED"))
                .andExpect(jsonPath("$[1]").value("TERMS_ACCEPTED"));
        verify(audit, never()).search(any(), any(), any(), any(), any(), any(), any());
    }
}
