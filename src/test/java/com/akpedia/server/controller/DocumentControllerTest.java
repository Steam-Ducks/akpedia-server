package com.akpedia.server.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.PdfConversionRejectedException;
import com.akpedia.server.exception.PdfConversionUnavailableException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.service.DocumentUploadService;

/**
 * Exercises the HTTP layer with {@link DocumentUploadService} mocked. What matters here is
 * the translation: which status each failure becomes, and that a successful upload answers
 * 201 with the stored metadata.
 */
@WebMvcTest(controllers = DocumentController.class)
@Import(ApiExceptionHandler.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DocumentUploadService uploadService;

    private static Document document(ProcessingStatus processingStatus, String processingError) {
        Sector sector = new Sector("TI", "setor de TI");
        Category category = new Category("Manuais", "manuais tecnicos");
        User creator = new User("Ana", "ana@akpedia.test", "hash", sector);
        Document document = new Document("relatorio.pdf", "application/pdf", 2048L, category, creator, DocumentStatus.DRAFT);
        ReflectionTestUtils.setField(category, "id", 10L);
        ReflectionTestUtils.setField(creator, "id", 20L);
        ReflectionTestUtils.setField(document, "id", 1L);
        document.setProcessingStatus(processingStatus);
        document.setProcessingError(processingError);
        return document;
    }

    @Test
    @DisplayName("POST /api/v1/documents converts, saves, indexes and answers 201 with the metadata")
    void uploadStoresConvertedDocument() throws Exception {
        given(uploadService.upload(any(), eq("relatorio.docx"),
                eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                eq(10L), eq(20L), isNull(), isNull()))
                .willReturn(document(ProcessingStatus.COMPLETED, null));

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "relatorio.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "conteudo".getBytes()))
                        .param("categoryId", "10")
                        .param("creatorId", "20"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("relatorio.pdf"))
                .andExpect(jsonPath("$.mime_type").value("application/pdf"))
                .andExpect(jsonPath("$.category_id").value(10))
                .andExpect(jsonPath("$.creator_id").value(20))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.processing_status").value("COMPLETED"))
                .andExpect(jsonPath("$.processing_error").value(nullValue()));
    }

    @Test
    @DisplayName("a document that could not be indexed still answers 201, with the reason exposed")
    void uploadDegradedByFailedIndexingStillAnswers201() throws Exception {
        given(uploadService.upload(any(), any(), any(), eq(10L), eq(20L), isNull(), isNull()))
                .willReturn(document(ProcessingStatus.FAILED, "akpedia-ml esta fora do ar"));

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "relatorio.docx", "application/msword", "conteudo".getBytes()))
                        .param("categoryId", "10")
                        .param("creatorId", "20"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.processing_status").value("FAILED"))
                .andExpect(jsonPath("$.processing_error").value("akpedia-ml esta fora do ar"));
    }

    @Test
    @DisplayName("an unknown category answers 404, not 500")
    void unknownCategoryBecomes404() throws Exception {
        willThrow(new CategoryNotFoundException(999L))
                .given(uploadService).upload(any(), any(), any(), eq(999L), anyLong(), any(), any());

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                        .param("categoryId", "999")
                        .param("creatorId", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("category_not_found"));
    }

    @Test
    @DisplayName("an unknown creator answers 404, not 500")
    void unknownCreatorBecomes404() throws Exception {
        willThrow(new UserNotFoundException(999L))
                .given(uploadService).upload(any(), any(), any(), anyLong(), eq(999L), any(), any());

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                        .param("categoryId", "1")
                        .param("creatorId", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user_not_found"));
    }

    @Test
    @DisplayName("a file Gotenberg refuses to convert answers 422")
    void conversionRejectionBecomes422() throws Exception {
        willThrow(new PdfConversionRejectedException("refused", HttpStatus.BAD_REQUEST, null))
                .given(uploadService).upload(any(), any(), any(), anyLong(), anyLong(), any(), any());

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "a.xyz", "application/octet-stream", new byte[] {1}))
                        .param("categoryId", "1")
                        .param("creatorId", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("pdf_conversion_rejected"));
    }

    @Test
    @DisplayName("Gotenberg being down answers 503, not 500")
    void conversionUnavailableBecomes503() throws Exception {
        willThrow(new PdfConversionUnavailableException("gotenberg is down", null))
                .given(uploadService).upload(any(), any(), any(), anyLong(), anyLong(), any(), any());

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                        .param("categoryId", "1")
                        .param("creatorId", "1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("pdf_conversion_unavailable"));
    }

    @Test
    @DisplayName("a missing categoryId answers 400, not 500")
    void missingCategoryIdBecomes400() throws Exception {
        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                        .param("creatorId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    @DisplayName("a non-numeric categoryId answers 400, not 500")
    void nonNumericCategoryIdBecomes400() throws Exception {
        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                        .param("categoryId", "abc")
                        .param("creatorId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

}
