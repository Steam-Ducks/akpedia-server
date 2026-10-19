package com.akpedia.server.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.dto.DocumentAnalysisResponse;
import com.akpedia.server.dto.DocumentFileDescriptor;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.SubmissionStatus;
import com.akpedia.server.dto.SubmissionSummary;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.CategorySuggestionUnavailableException;
import com.akpedia.server.exception.DocumentArchivedException;
import com.akpedia.server.exception.DocumentFileNotFoundException;
import com.akpedia.server.exception.DocumentNotFoundException;
import com.akpedia.server.exception.DocumentNotProcessedException;
import com.akpedia.server.exception.InvalidDocumentListRequestException;
import com.akpedia.server.exception.InvalidDocumentUploadException;
import com.akpedia.server.exception.PdfConversionRejectedException;
import com.akpedia.server.exception.PdfConversionUnavailableException;
import com.akpedia.server.exception.UnknownSuggestedCategoryException;
import com.akpedia.server.exception.UnsupportedDocumentFormatException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.service.DocumentAnalysisService;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentListService;
import com.akpedia.server.service.DocumentSubmissionService;
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

    @MockBean
    private DocumentFileService fileService;

    @MockBean
    private DocumentListService listService;

    @MockBean
    private DocumentAnalysisService analysisService;

    @MockBean
    private DocumentSubmissionService submissionService;

    /** Fixed validators, so the conditional-request cases can send the exact values back. */
    private static final Instant LAST_MODIFIED = Instant.parse("2026-09-24T22:31:05Z");
    private static final String ETAG = "\"1-17-1790634665000\"";

    /** A freshly uploaded document, the way the service hands one back: stored and awaiting approval. */
    private static Document document() {
        Sector sector = new Sector("TI", "setor de TI");
        Category category = new Category("Manuais", "manuais tecnicos");
        User creator = new User("Ana", "ana@akpedia.test", "hash", sector);
        Document document = new Document(
                "relatorio.pdf", "application/pdf", 2048L, category, creator, DocumentStatus.PENDING_APPROVAL);
        ReflectionTestUtils.setField(category, "id", 10L);
        ReflectionTestUtils.setField(creator, "id", 20L);
        ReflectionTestUtils.setField(document, "id", 1L);
        return document;
    }

    @Test
    @DisplayName("POST /api/v1/documents converts, saves and answers 201 with the document pending approval")
    void uploadStoresConvertedDocument() throws Exception {
        given(uploadService.upload(any(), eq("relatorio.docx"),
                eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                eq(10L), eq(20L), isNull(), isNull(), isNull(), isNull()))
                .willReturn(document());

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
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.processing_status").value("PENDING"))
                .andExpect(jsonPath("$.processing_error").value(nullValue()))
                .andExpect(jsonPath("$.suggested_category_id").value(nullValue()))
                .andExpect(jsonPath("$.category_confidence").value(nullValue()));
    }

    @Test
    @DisplayName("the suggested category and its confidence reach the service and come back in the response")
    void uploadCarriesTheCategorySuggestion() throws Exception {
        Document document = document();
        Category suggested = new Category("Tecnico", "Documentos tecnicos");
        ReflectionTestUtils.setField(suggested, "id", 11L);
        document.setSuggestedCategory(suggested);
        document.setCategoryConfidence(new BigDecimal("0.87"));
        given(uploadService.upload(any(), any(), any(), eq(10L), eq(20L), isNull(), isNull(),
                eq(11L), eq(new BigDecimal("0.87"))))
                .willReturn(document);

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "relatorio.docx", "application/msword", "conteudo".getBytes()))
                        .param("categoryId", "10")
                        .param("creatorId", "20")
                        .param("suggestedCategoryId", "11")
                        .param("categoryConfidence", "0.87"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.suggested_category_id").value(11))
                .andExpect(jsonPath("$.category_confidence").value(0.87));
    }

    @Test
    @DisplayName("an upload with no creatorId is accepted and handed to the service without one")
    void uploadWithoutCreatorIsAccepted() throws Exception {
        given(uploadService.upload(any(), any(), any(), eq(10L), isNull(), isNull(), isNull(), isNull(), isNull()))
                .willReturn(document());

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "relatorio.docx", "application/msword", "conteudo".getBytes()))
                        .param("categoryId", "10"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    @DisplayName("a confidence the service refuses answers 400, not 500")
    void invalidConfidenceBecomes400() throws Exception {
        willThrow(new InvalidDocumentUploadException("categoryConfidence must be between 0 and 1."))
                .given(uploadService).upload(any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any());

        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                        .param("categoryId", "1")
                        .param("creatorId", "1")
                        .param("suggestedCategoryId", "2")
                        .param("categoryConfidence", "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    @DisplayName("a non-numeric categoryConfidence answers 400, not 500")
    void nonNumericConfidenceBecomes400() throws Exception {
        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                        .param("categoryId", "1")
                        .param("suggestedCategoryId", "2")
                        .param("categoryConfidence", "alta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    @DisplayName("an unknown category answers 404, not 500")
    void unknownCategoryBecomes404() throws Exception {
        willThrow(new CategoryNotFoundException(999L))
                .given(uploadService).upload(any(), any(), any(), eq(999L), anyLong(), any(), any(), any(), any());

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
                .given(uploadService).upload(any(), any(), any(), anyLong(), eq(999L), any(), any(), any(), any());

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
                .given(uploadService).upload(any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any());

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
                .given(uploadService).upload(any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any());

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

    /** A described PDF the way the service hands one over, with both of its steps stubbed. */
    private DocumentFileDescriptor storedPdf(String filename, byte[] content) {
        DocumentFileDescriptor file = new DocumentFileDescriptor(
                1L, filename, MediaType.APPLICATION_PDF, content.length, LAST_MODIFIED, ETAG);
        given(fileService.describe(1L)).willReturn(file);
        given(fileService.contentOf(file)).willReturn(content);
        return file;
    }

    @Test
    @DisplayName("GET /api/v1/documents/{id}/file answers the PDF inline, with its type and name")
    void fileIsServedInlineForTheBrowser() throws Exception {
        byte[] pdf = "%PDF-1.4 conteudo".getBytes();
        storedPdf("relatorio.pdf", pdf);

        mvc.perform(get("/api/v1/documents/1/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inline")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("relatorio.pdf")))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, pdf.length))
                .andExpect(content().bytes(pdf));
    }

    @Test
    @DisplayName("the response carries its validators and is revalidated instead of kept in a shared cache")
    void fileResponseIsPrivateAndRevalidated() throws Exception {
        storedPdf("relatorio.pdf", "%PDF-1.4".getBytes());

        mvc.perform(get("/api/v1/documents/1/file"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().dateValue(HttpHeaders.LAST_MODIFIED, LAST_MODIFIED.toEpochMilli()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")));
    }

    @Test
    @DisplayName("a browser that already holds the document gets 304 and no bytes")
    void unchangedDocumentAnswers304() throws Exception {
        storedPdf("relatorio.pdf", "%PDF-1.4".getBytes());

        mvc.perform(get("/api/v1/documents/1/file").header(HttpHeaders.IF_NONE_MATCH, ETAG))
                .andExpect(status().isNotModified())
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    @DisplayName("a stale copy is not confirmed: a different validator gets the file again")
    void staleCopyGetsTheFileAgain() throws Exception {
        byte[] pdf = "%PDF-1.4 conteudo".getBytes();
        storedPdf("relatorio.pdf", pdf);

        mvc.perform(get("/api/v1/documents/1/file").header(HttpHeaders.IF_NONE_MATCH, "\"1-1-1\""))
                .andExpect(status().isOk())
                .andExpect(content().bytes(pdf));
    }

    @Test
    @DisplayName("the route advertises ranges and answers a Range request with 206 and just that slice")
    void rangeRequestAnswersPartialContent() throws Exception {
        byte[] pdf = "%PDF-1.4 conteudo".getBytes();
        storedPdf("relatorio.pdf", pdf);

        mvc.perform(get("/api/v1/documents/1/file").header(HttpHeaders.RANGE, "bytes=0-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-4/" + pdf.length))
                .andExpect(content().bytes("%PDF-".getBytes()));
    }

    @Test
    @DisplayName("a range past the end of the file answers 416 with the real size")
    void unsatisfiableRangeAnswers416() throws Exception {
        byte[] pdf = "%PDF-1.4".getBytes();
        storedPdf("relatorio.pdf", pdf);

        mvc.perform(get("/api/v1/documents/1/file").header(HttpHeaders.RANGE, "bytes=9000-9100"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */" + pdf.length));
    }

    @Test
    @DisplayName("a name with accents survives the Content-Disposition header")
    void fileNameWithAccentsIsEncoded() throws Exception {
        storedPdf("relatório de produção.pdf", "%PDF-1.4".getBytes());

        mvc.perform(get("/api/v1/documents/1/file"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("UTF-8''")));
    }

    @Test
    @DisplayName("the file response tells the browser not to sniff another type out of the bytes")
    void fileResponseForbidsMimeSniffing() throws Exception {
        storedPdf("relatorio.pdf", "%PDF-1.4".getBytes());

        mvc.perform(get("/api/v1/documents/1/file"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("anything that is not a PDF is offered as a download, never rendered inline")
    void nonPdfIsServedAsAttachment() throws Exception {
        byte[] markup = "<script>alert(1)</script>".getBytes();
        DocumentFileDescriptor file = new DocumentFileDescriptor(
                1L, "pagina.html", MediaType.TEXT_HTML, markup.length, LAST_MODIFIED, ETAG);
        given(fileService.describe(1L)).willReturn(file);
        given(fileService.contentOf(file)).willReturn(markup);

        mvc.perform(get("/api/v1/documents/1/file"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")));
    }

    @Test
    @DisplayName("an unknown document answers 404, not 500")
    void unknownDocumentFileBecomes404() throws Exception {
        willThrow(new DocumentNotFoundException(999L)).given(fileService).describe(999L);

        mvc.perform(get("/api/v1/documents/999/file"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("document_not_found"));
    }

    @Test
    @DisplayName("a document whose binary is missing answers 404 with its own code")
    void documentWithoutStoredFileBecomes404() throws Exception {
        DocumentFileDescriptor file = new DocumentFileDescriptor(
                1L, "relatorio.pdf", MediaType.APPLICATION_PDF, 8L, LAST_MODIFIED, ETAG);
        given(fileService.describe(1L)).willReturn(file);
        willThrow(new DocumentFileNotFoundException(1L)).given(fileService).contentOf(file);

        mvc.perform(get("/api/v1/documents/1/file"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("document_file_not_found"));
    }

    @Test
    @DisplayName("an archived document answers 403")
    void archivedDocumentBecomes403() throws Exception {
        willThrow(new DocumentArchivedException(1L)).given(fileService).describe(1L);

        mvc.perform(get("/api/v1/documents/1/file"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("document_archived"));
    }

    @Test
    @DisplayName("a document akpedia-ml is still indexing answers 409, so a retry makes sense")
    void notIndexedDocumentBecomes409() throws Exception {
        willThrow(new DocumentNotProcessedException(1L, ProcessingStatus.PROCESSING)).given(fileService).describe(1L);

        mvc.perform(get("/api/v1/documents/1/file"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_not_processed"));
    }

    @Test
    @DisplayName("a non-numeric document id answers 400, not 500")
    void nonNumericDocumentIdBecomes400() throws Exception {
        mvc.perform(get("/api/v1/documents/abc/file"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/documents/submissions
    // ------------------------------------------------------------------

    private static PageResponse<SubmissionSummary> onePage(SubmissionSummary summary) {
        return new PageResponse<>(List.of(summary), 0, 10, 1, 1);
    }

    @Test
    @DisplayName("GET /documents/submissions answers UNDER_REVIEW without review fields")
    void submissionsUnderReview() throws Exception {
        given(submissionService.submissions(eq(20L), isNull(), isNull())).willReturn(onePage(
                new SubmissionSummary(1L, "manual.pdf", null, "Manuais",
                        SubmissionStatus.UNDER_REVIEW, null, null,
                        OffsetDateTime.parse("2026-10-16T11:20:00Z"), null)));

        mvc.perform(get("/api/v1/documents/submissions").param("creatorId", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].document_id").value(1))
                .andExpect(jsonPath("$.content[0].submission_status").value("UNDER_REVIEW"))
                // Absent, not null: a document under review has no rejection to report.
                .andExpect(jsonPath("$.content[0].review_comment").doesNotExist())
                .andExpect(jsonPath("$.content[0].reviewed_at").doesNotExist())
                // Only the review fields are left out: the rest stay as nulls, so every row has the
                // same keys.
                .andExpect(jsonPath("$.content[0]").value(hasKey("description")))
                .andExpect(jsonPath("$.content[0].description").value(nullValue()))
                .andExpect(jsonPath("$.content[0]").value(hasKey("updated_at")))
                .andExpect(jsonPath("$.content[0].updated_at").value(nullValue()));
    }

    @Test
    @DisplayName("GET /documents/submissions answers PROCESSING for an approved document not yet indexed")
    void submissionsProcessing() throws Exception {
        given(submissionService.submissions(eq(20L), isNull(), isNull())).willReturn(onePage(
                new SubmissionSummary(4L, "manual.pdf", null, "Manuais",
                        SubmissionStatus.PROCESSING, null, null,
                        OffsetDateTime.parse("2026-10-16T11:20:00Z"), null)));

        mvc.perform(get("/api/v1/documents/submissions").param("creatorId", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].submission_status").value("PROCESSING"))
                .andExpect(jsonPath("$.content[0].review_comment").doesNotExist());
    }

    @Test
    @DisplayName("GET /documents/submissions answers INDEXING_FAILED for an approved document whose indexing failed")
    void submissionsIndexingFailed() throws Exception {
        given(submissionService.submissions(eq(20L), isNull(), isNull())).willReturn(onePage(
                new SubmissionSummary(5L, "manual.pdf", null, "Manuais",
                        SubmissionStatus.INDEXING_FAILED, null, null,
                        OffsetDateTime.parse("2026-10-16T11:20:00Z"), null)));

        mvc.perform(get("/api/v1/documents/submissions").param("creatorId", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].submission_status").value("INDEXING_FAILED"))
                .andExpect(jsonPath("$.content[0].review_comment").doesNotExist());
    }

    @Test
    @DisplayName("GET /documents/submissions answers PUBLISHED for an approved, indexed document")
    void submissionsPublished() throws Exception {
        given(submissionService.submissions(eq(20L), isNull(), isNull())).willReturn(onePage(
                new SubmissionSummary(2L, "norma.pdf", "descricao", "Regulatorio",
                        SubmissionStatus.PUBLISHED, null, null,
                        OffsetDateTime.parse("2026-10-16T11:20:00Z"),
                        OffsetDateTime.parse("2026-10-16T13:00:00Z"))));

        mvc.perform(get("/api/v1/documents/submissions").param("creatorId", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].submission_status").value("PUBLISHED"))
                .andExpect(jsonPath("$.content[0].category").value("Regulatorio"))
                .andExpect(jsonPath("$.content[0].review_comment").doesNotExist());
    }

    @Test
    @DisplayName("GET /documents/submissions answers RETURNED with the reason it came back")
    void submissionsReturned() throws Exception {
        given(submissionService.submissions(eq(20L), isNull(), isNull())).willReturn(onePage(
                new SubmissionSummary(3L, "contrato.pdf", null, "Juridico",
                        SubmissionStatus.RETURNED, "Falta a secao de revisao A.",
                        OffsetDateTime.parse("2026-10-16T12:00:00Z"),
                        OffsetDateTime.parse("2026-10-16T11:20:00Z"), null)));

        mvc.perform(get("/api/v1/documents/submissions").param("creatorId", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].submission_status").value("RETURNED"))
                .andExpect(jsonPath("$.content[0].review_comment").value("Falta a secao de revisao A."))
                .andExpect(jsonPath("$.content[0].reviewed_at").exists());
    }

    @Test
    @DisplayName("GET /documents/submissions carries the paging totals and works without creatorId")
    void submissionsCarryPagingTotals() throws Exception {
        given(submissionService.submissions(isNull(), eq(1), eq(10))).willReturn(
                new PageResponse<>(List.of(), 1, 10, 23, 3));

        mvc.perform(get("/api/v1/documents/submissions").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total_elements").value(23))
                .andExpect(jsonPath("$.total_pages").value(3))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    @DisplayName("GET /documents/submissions answers 404 for a creatorId no user has")
    void submissionsUnknownCreator() throws Exception {
        willThrow(new UserNotFoundException(404L))
                .given(submissionService).submissions(eq(404L), isNull(), isNull());

        mvc.perform(get("/api/v1/documents/submissions").param("creatorId", "404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user_not_found"));
    }

    @Test
    @DisplayName("GET /documents/submissions answers 400 for a size out of bounds")
    void submissionsSizeOutOfBounds() throws Exception {
        willThrow(new InvalidDocumentListRequestException("size must be between 1 and 50."))
                .given(submissionService).submissions(isNull(), isNull(), eq(0));

        mvc.perform(get("/api/v1/documents/submissions").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/documents/analyze
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "manual.pdf,       application/pdf",
        "manual.doc,       application/msword",
        "manual.docx,      application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "planilha.xls,     application/vnd.ms-excel",
        "planilha.xlsx,    application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    })
    @DisplayName("POST /api/v1/documents/analyze answers 200 with the suggestion for each accepted format")
    void analyzeAcceptsEverySupportedFormat(String filename, String contentType) throws Exception {
        given(analysisService.analyze(any(), eq(filename), eq(contentType)))
                .willReturn(new DocumentAnalysisResponse(
                        "Manual do Trem de Pouso", "Este manual descreve a inspecao.", 7L, new BigDecimal("0.873")));

        mvc.perform(multipart("/api/v1/documents/analyze")
                        .file(new MockMultipartFile("file", filename, contentType, "conteudo".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Manual do Trem de Pouso"))
                .andExpect(jsonPath("$.description").value("Este manual descreve a inspecao."))
                .andExpect(jsonPath("$.suggested_category_id").value(7))
                .andExpect(jsonPath("$.confidence").value(0.873));
    }

    @Test
    @DisplayName("POST /api/v1/documents/analyze answers 200 with null category when there is no suggestion")
    void analyzeAnswersOkWithoutACategory() throws Exception {
        given(analysisService.analyze(any(), eq("manual.pdf"), eq("application/pdf")))
                .willReturn(new DocumentAnalysisResponse("Manual do Trem de Pouso", null, null, null));

        mvc.perform(multipart("/api/v1/documents/analyze")
                        .file(new MockMultipartFile("file", "manual.pdf", "application/pdf", "%PDF-1.4".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Manual do Trem de Pouso"))
                // Present and null, not missing: the screen tells "no suggestion" apart from an old contract.
                .andExpect(jsonPath("$.suggested_category_id").value(nullValue()))
                .andExpect(jsonPath("$.confidence").value(nullValue()))
                .andExpect(jsonPath("$.description").value(nullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/documents/analyze answers 415 and the accepted list for a refused format")
    void analyzeRejectsUnsupportedFormat() throws Exception {
        willThrow(new UnsupportedDocumentFormatException("apresentacao.pptx", List.of("pdf", "doc", "docx", "xls", "xlsx")))
                .given(analysisService).analyze(any(), eq("apresentacao.pptx"), eq("application/vnd.ms-powerpoint"));

        mvc.perform(multipart("/api/v1/documents/analyze")
                        .file(new MockMultipartFile("file", "apresentacao.pptx",
                                "application/vnd.ms-powerpoint", "conteudo".getBytes())))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_format"))
                // The screen reads this list instead of hardcoding one that would drift from the server's.
                .andExpect(jsonPath("$.supported_extensions").value(
                        org.hamcrest.Matchers.contains("pdf", "doc", "docx", "xls", "xlsx")))
                .andExpect(jsonPath("$.message", containsString("apresentacao.pptx")));
    }

    @Test
    @DisplayName("POST /api/v1/documents/analyze answers 503 telling the screen to let the user choose")
    void analyzeAnswersUnavailableWhenMlIsDown() throws Exception {
        willThrow(new CategorySuggestionUnavailableException(new RuntimeException("connection refused")))
                .given(analysisService).analyze(any(), eq("manual.pdf"), eq("application/pdf"));

        mvc.perform(multipart("/api/v1/documents/analyze")
                        .file(new MockMultipartFile("file", "manual.pdf", "application/pdf", "%PDF-1.4".getBytes())))
                .andExpect(status().isServiceUnavailable())
                // Its own code: the screen recovers from this one by enabling manual input.
                .andExpect(jsonPath("$.code").value("category_suggestion_unavailable"))
                .andExpect(jsonPath("$.message", containsString("manualmente")));
    }

    @Test
    @DisplayName("POST /api/v1/documents/analyze answers 502 when the suggestion is outside the catalog")
    void analyzeAnswersBadGatewayForACategoryNotInTheCatalog() throws Exception {
        willThrow(new UnknownSuggestedCategoryException("Financeiro"))
                .given(analysisService).analyze(any(), eq("manual.pdf"), eq("application/pdf"));

        mvc.perform(multipart("/api/v1/documents/analyze")
                        .file(new MockMultipartFile("file", "manual.pdf", "application/pdf", "%PDF-1.4".getBytes())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("unknown_suggested_category"))
                .andExpect(jsonPath("$.message", containsString("Financeiro")));
    }

}
