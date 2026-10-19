package com.akpedia.server.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.service.DocumentAnalysisService;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentListService;
import com.akpedia.server.service.DocumentUploadService;

/**
 * The approver opening a document from the review queue.
 *
 * <p>Unlike the other route tests, {@link DocumentFileService} is the real one here, with only its
 * repositories mocked: what is under test is the service's rule on which states open, as the
 * status code the route answers for it.
 */
@WebMvcTest(controllers = DocumentController.class)
@Import({ApiExceptionHandler.class, DocumentFileService.class})
class PendingDocumentFileTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 conteudo".getBytes();

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DocumentRepository documents;

    @MockBean
    private DocumentFileRepository documentFiles;

    @MockBean
    private DocumentUploadService uploadService;

    @MockBean
    private DocumentListService listService;

    @MockBean
    private DocumentAnalysisService analysisService;

    private void stored(DocumentStatus status, ProcessingStatus processingStatus) {
        User creator = new User("Ana", "ana@akpedia.test", "hash", new Sector("TI", null));
        Document document = new Document(
                "relatorio.pdf", MediaType.APPLICATION_PDF_VALUE, (long) PDF_BYTES.length,
                new Category("Manuais", null), creator, status);
        document.setProcessingStatus(processingStatus);
        ReflectionTestUtils.setField(document, "id", 7L);
        given(documents.findById(7L)).willReturn(Optional.of(document));
        given(documentFiles.findByDocumentId(7L)).willReturn(Optional.of(new DocumentFile(document, PDF_BYTES)));
    }

    @Test
    @DisplayName("the file of a document awaiting approval answers 200 with its bytes")
    void pendingDocumentFileIsServed() throws Exception {
        stored(DocumentStatus.PENDING_APPROVAL, ProcessingStatus.PENDING);

        mvc.perform(get("/api/v1/documents/7/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF_BYTES));
    }

    @Test
    @DisplayName("only indexing in flight still answers 409")
    void documentBeingIndexedIsAConflict() throws Exception {
        stored(DocumentStatus.APPROVED, ProcessingStatus.PROCESSING);

        mvc.perform(get("/api/v1/documents/7/file"))
                .andExpect(status().isConflict());
    }
}
