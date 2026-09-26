package com.akpedia.server.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.dto.DocumentSummary;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.InvalidDocumentListRequestException;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentListService;
import com.akpedia.server.service.DocumentUploadService;

@WebMvcTest(controllers = DocumentController.class)
@Import(ApiExceptionHandler.class)
class DocumentListHttpTest {

    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.of(2026, 9, 20, 14, 30, 0, 0, ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DocumentUploadService uploadService;

    @MockBean
    private DocumentFileService fileService;

    @MockBean
    private DocumentListService listService;

    @Test
    @DisplayName("GET /api/v1/documents answers each document with the eight agreed fields")
    void documentCarriesTheAgreedFormat() throws Exception {
        given(listService.recent(null)).willReturn(List.of(new DocumentSummary(
                7L, "manual.pdf", null, "application/pdf", "Manuais", "Mariana Costa", UPDATED_AT,
                "Manual de teste do akpedia.")));

        mvc.perform(get("/api/v1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].*", hasSize(8)))
                .andExpect(jsonPath("$[0].document_id").value(7))
                .andExpect(jsonPath("$[0].name").value("manual.pdf"))
                .andExpect(jsonPath("$[0].mime_type").value("application/pdf"))
                .andExpect(jsonPath("$[0].category").value("Manuais"))
                .andExpect(jsonPath("$[0].responsible_name").value("Mariana Costa"))
                .andExpect(jsonPath("$[0].updated_at").value("2026-09-20T14:30:00Z"))
                .andExpect(jsonPath("$[0].excerpt").value("Manual de teste do akpedia."));
    }

    @Test
    @DisplayName("the limit is handed to the service")
    void limitIsPassedOn() throws Exception {
        given(listService.recent(3)).willReturn(List.of());

        mvc.perform(get("/api/v1/documents").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("an invalid limit answers 400 with the error body")
    void invalidLimitIsABadRequest() throws Exception {
        given(listService.recent(0)).willThrow(new InvalidDocumentListRequestException("limit must be between 1 and 50."));

        mvc.perform(get("/api/v1/documents").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 50."));
    }
}
