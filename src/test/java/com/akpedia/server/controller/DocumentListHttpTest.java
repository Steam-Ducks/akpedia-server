package com.akpedia.server.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.PendingDocument;
import com.akpedia.server.dto.PendingDocument.SuggestedCategory;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.InvalidDocumentListRequestException;
import com.akpedia.server.service.DocumentAnalysisService;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentListService;
import com.akpedia.server.service.DocumentSubmissionService;
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

    @MockBean
    private DocumentSubmissionService submissionService;

    @MockBean
    private DocumentAnalysisService analysisService;

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

    private static PendingDocument pending(long id, String name, int day) {
        return new PendingDocument(
                id, name, "Mariana Costa", "Engenharia", UPDATED_AT.withDayOfMonth(day),
                new SuggestedCategory(3L, "Tecnico"), new BigDecimal("0.870"));
    }

    @Test
    @DisplayName("GET /api/v1/documents/pending answers a page of the queue, each document with the seven agreed fields")
    void pendingDocumentCarriesTheAgreedFormat() throws Exception {
        given(listService.pending(null, null)).willReturn(
                new PageResponse<>(List.of(pending(7L, "manual.pdf", 20)), 0, 20, 1, 1));

        mvc.perform(get("/api/v1/documents/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].*", hasSize(7)))
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].name").value("manual.pdf"))
                .andExpect(jsonPath("$.content[0].creator_name").value("Mariana Costa"))
                .andExpect(jsonPath("$.content[0].sector_name").value("Engenharia"))
                .andExpect(jsonPath("$.content[0].created_at").value("2026-09-20T14:30:00Z"))
                .andExpect(jsonPath("$.content[0].suggested_category.id").value(3))
                .andExpect(jsonPath("$.content[0].suggested_category.name").value("Tecnico"))
                .andExpect(jsonPath("$.content[0].confidence").value(0.87))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.total_pages").value(1));
    }

    @Test
    @DisplayName("several pending documents keep the service's oldest-first order")
    void severalPendingDocumentsKeepTheirOrder() throws Exception {
        given(listService.pending(null, null)).willReturn(new PageResponse<>(
                List.of(pending(4L, "antigo.pdf", 1), pending(9L, "meio.pdf", 2), pending(2L, "recente.pdf", 3)),
                0, 20, 3, 1));

        mvc.perform(get("/api/v1/documents/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(4, 9, 2)))
                .andExpect(jsonPath("$.content[*].created_at",
                        contains("2026-09-01T14:30:00Z", "2026-09-02T14:30:00Z", "2026-09-03T14:30:00Z")))
                .andExpect(jsonPath("$.total_elements").value(3));
    }

    @Test
    @DisplayName("page and size are handed to the service, and the page answered says where it sits")
    void pageAndSizeArePassedOn() throws Exception {
        given(listService.pending(1, 2)).willReturn(
                new PageResponse<>(List.of(pending(5L, "terceiro.pdf", 3)), 1, 2, 3, 2));

        mvc.perform(get("/api/v1/documents/pending").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(5)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total_elements").value(3))
                .andExpect(jsonPath("$.total_pages").value(2));
    }

    @Test
    @DisplayName("a pending document with no suggestion answers both suggestion fields as null")
    void documentWithoutSuggestionAnswersNulls() throws Exception {
        given(listService.pending(null, null)).willReturn(new PageResponse<>(
                List.of(new PendingDocument(7L, "manual.pdf", "Mariana Costa", "Engenharia", UPDATED_AT, null, null)),
                0, 20, 1, 1));

        mvc.perform(get("/api/v1/documents/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].suggested_category", nullValue()))
                .andExpect(jsonPath("$.content[0].confidence", nullValue()));
    }

    @Test
    @DisplayName("an invalid page size answers 400 with the error body")
    void invalidPageSizeIsABadRequest() throws Exception {
        given(listService.pending(null, 0)).willThrow(new InvalidDocumentListRequestException("size must be between 1 and 50."));

        mvc.perform(get("/api/v1/documents/pending").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("size must be between 1 and 50."));
    }

    @Test
    @DisplayName("a page that is not a number answers 400 without reaching the service")
    void nonNumericPageIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/documents/pending").param("page", "primeira"))
                .andExpect(status().isBadRequest());
    }
}
