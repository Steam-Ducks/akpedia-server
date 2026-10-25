package com.akpedia.server.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.dto.ApproveDocumentRequest;
import com.akpedia.server.dto.DocumentReviewResponse;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.DocumentNotFoundException;
import com.akpedia.server.exception.DocumentNotPendingApprovalException;
import com.akpedia.server.service.DocumentReviewService;

/**
 * The approve and reject routes: what reaches the service, the JSON that comes back, and how
 * each refusal is answered.
 */
@WebMvcTest(controllers = DocumentReviewController.class)
class DocumentReviewControllerTest {

    private static final OffsetDateTime DECIDED_AT = OffsetDateTime.parse("2026-10-20T14:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DocumentReviewService reviewService;

    private static DocumentReviewResponse approved(String name, String description, long categoryId) {
        return new DocumentReviewResponse(7L, name, description, categoryId, DocumentStatus.APPROVED,
                ProcessingStatus.PROCESSING, 1L, DECIDED_AT, null, DECIDED_AT);
    }

    private static DocumentReviewResponse rejected(String comment) {
        return new DocumentReviewResponse(7L, "manual.pdf", null, 3L, DocumentStatus.REJECTED,
                ProcessingStatus.PENDING, null, null, comment, DECIDED_AT);
    }

    // ------------------------------------------------------------------
    // POST /documents/{id}/approve
    // ------------------------------------------------------------------

    @Test
    @DisplayName("approve with no body approves without corrections")
    void approveWithoutBody() throws Exception {
        given(reviewService.approve(7L, ApproveDocumentRequest.NONE))
                .willReturn(approved("manual.pdf", null, 3L));

        mvc.perform(post("/api/v1/documents/7/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.processing_status").value("PROCESSING"))
                .andExpect(jsonPath("$.approver_id").value(1))
                .andExpect(jsonPath("$.approved_at").exists())
                .andExpect(jsonPath("$.reviewed_at").exists())
                // Present as null rather than absent: every decision answers the same keys.
                .andExpect(jsonPath("$").value(hasKey("review_comment")))
                .andExpect(jsonPath("$.review_comment").value(nullValue()));
    }

    @Test
    @DisplayName("approve with an empty body also approves without corrections")
    void approveWithEmptyObject() throws Exception {
        given(reviewService.approve(7L, ApproveDocumentRequest.NONE))
                .willReturn(approved("manual.pdf", null, 3L));

        mvc.perform(post("/api/v1/documents/7/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(reviewService).approve(7L, ApproveDocumentRequest.NONE);
    }

    @Test
    @DisplayName("approve with corrections passes name, description and categoryId to the service")
    void approveWithCorrections() throws Exception {
        ApproveDocumentRequest corrections = new ApproveDocumentRequest("Manual de calibracao", "revisado", 4L);
        given(reviewService.approve(7L, corrections))
                .willReturn(approved("Manual de calibracao", "revisado", 4L));

        mvc.perform(post("/api/v1/documents/7/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Manual de calibracao", "description": "revisado", "categoryId": 4}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Manual de calibracao"))
                .andExpect(jsonPath("$.description").value("revisado"))
                .andExpect(jsonPath("$.category_id").value(4));
    }

    @Test
    @DisplayName("approve with a blank name answers 400 and approves nothing")
    void approveWithBlankName() throws Exception {
        mvc.perform(post("/api/v1/documents/7/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(containsString("name")));

        verify(reviewService, never()).approve(anyLong(), any());
    }

    @Test
    @DisplayName("approve with a name past 255 characters answers 400")
    void approveWithNameTooLong() throws Exception {
        mvc.perform(post("/api/v1/documents/7/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\"}".formatted("a".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    @DisplayName("approve with a corrected category that does not exist answers 404")
    void approveWithUnknownCategory() throws Exception {
        given(reviewService.approve(eq(7L), any())).willThrow(new CategoryNotFoundException(99L));

        mvc.perform(post("/api/v1/documents/7/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\": 99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("category_not_found"));
    }

    @Test
    @DisplayName("approve on a document not waiting on a decision answers 409")
    void approveConflict() throws Exception {
        given(reviewService.approve(eq(7L), any()))
                .willThrow(new DocumentNotPendingApprovalException(7L, DocumentStatus.APPROVED));

        mvc.perform(post("/api/v1/documents/7/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_not_pending_approval"))
                .andExpect(jsonPath("$.message").value(containsString("APPROVED")));
    }

    @Test
    @DisplayName("approve on a document that does not exist answers 404")
    void approveUnknownDocument() throws Exception {
        given(reviewService.approve(eq(404L), any())).willThrow(new DocumentNotFoundException(404L));

        mvc.perform(post("/api/v1/documents/404/approve"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("document_not_found"));
    }

    // ------------------------------------------------------------------
    // POST /documents/{id}/reject
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reject with a comment returns the document to its author")
    void rejectWithComment() throws Exception {
        given(reviewService.reject(7L, "Falta a secao de revisao A."))
                .willReturn(rejected("Falta a secao de revisao A."));

        mvc.perform(post("/api/v1/documents/7/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\": \"Falta a secao de revisao A.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.review_comment").value("Falta a secao de revisao A."))
                .andExpect(jsonPath("$.reviewed_at").exists())
                .andExpect(jsonPath("$.approver_id").value(nullValue()));
    }

    @Test
    @DisplayName("reject without a comment answers 400 and rejects nothing")
    void rejectWithoutComment() throws Exception {
        mvc.perform(post("/api/v1/documents/7/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(containsString("comment")));

        verify(reviewService, never()).reject(anyLong(), anyString());
    }

    @Test
    @DisplayName("reject with a blank comment answers 400")
    void rejectWithBlankComment() throws Exception {
        mvc.perform(post("/api/v1/documents/7/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));

        verify(reviewService, never()).reject(anyLong(), anyString());
    }

    @Test
    @DisplayName("reject with no body at all answers 400 in the API's own error shape")
    void rejectWithoutBody() throws Exception {
        mvc.perform(post("/api/v1/documents/7/reject"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));

        verify(reviewService, never()).reject(anyLong(), anyString());
    }

    @Test
    @DisplayName("reject on a document not waiting on a decision answers 409")
    void rejectConflict() throws Exception {
        given(reviewService.reject(eq(7L), anyString()))
                .willThrow(new DocumentNotPendingApprovalException(7L, DocumentStatus.REJECTED));

        mvc.perform(post("/api/v1/documents/7/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\": \"motivo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_not_pending_approval"));
    }
}
