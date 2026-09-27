package com.akpedia.server.controller;

import static org.hamcrest.Matchers.containsString;
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

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import com.akpedia.server.dto.DocumentFileDescriptor;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.DocumentArchivedException;
import com.akpedia.server.exception.DocumentFileNotFoundException;
import com.akpedia.server.exception.DocumentNotFoundException;
import com.akpedia.server.exception.DocumentNotProcessedException;
import com.akpedia.server.exception.PdfConversionRejectedException;
import com.akpedia.server.exception.PdfConversionUnavailableException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentListService;
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

    /** Fixed validators, so the conditional-request cases can send the exact values back. */
    private static final Instant LAST_MODIFIED = Instant.parse("2026-09-24T22:31:05Z");
    private static final String ETAG = "\"1-17-1790634665000\"";

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
        willThrow(new DocumentNotProcessedException(1L, ProcessingStatus.PENDING)).given(fileService).describe(1L);

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

}
