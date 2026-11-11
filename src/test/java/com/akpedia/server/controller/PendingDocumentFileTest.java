package com.akpedia.server.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.security.Viewer;
import com.akpedia.server.service.CategoryAccessService;
import com.akpedia.server.service.DocumentAnalysisService;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentListService;
import com.akpedia.server.service.DocumentSubmissionService;
import com.akpedia.server.service.DocumentUploadService;

/**
 * The approver opening a document from the review queue.
 *
 * <p>Unlike the other route tests, {@link DocumentFileService} and {@link CategoryAccessService} are
 * the real ones here, with only their repositories mocked: what is under test is the services' rules
 * on which states and categories open, as the status code the route answers for them.
 *
 * <p>The document is in a category the viewer's sector does not see, so every case that opens it
 * does so through one of the two exceptions to the sector rule.
 */
@WebMvcTest(controllers = DocumentController.class)
// The endpoint is under test, not who may call it: SecurityConfigTest covers that.
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandler.class, DocumentFileService.class, CategoryAccessService.class})
class PendingDocumentFileTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 conteudo".getBytes();

    private static final long VIEWER_ID = 20L;
    private static final long BLOCKED_CATEGORY = 9L;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DocumentRepository documents;

    @MockBean
    private DocumentFileRepository documentFiles;

    @MockBean
    private CategoryRepository categories;

    @MockBean
    private DocumentUploadService uploadService;

    @MockBean
    private DocumentListService listService;

    @MockBean
    private DocumentSubmissionService submissionService;

    @MockBean
    private DocumentAnalysisService analysisService;

    @MockBean
    private CurrentUser currentUser;

    @BeforeEach
    void sectorSeesAnotherCategory() {
        given(categories.findIdsVisibleTo(VIEWER_ID)).willReturn(List.of(1L));
    }

    private void loggedInAs(boolean reviewer) {
        given(currentUser.viewer()).willReturn(new Viewer(VIEWER_ID, reviewer));
    }

    private void stored(DocumentStatus status, ProcessingStatus processingStatus) {
        User creator = new User("Ana", "ana@akpedia.test", new Sector("TI", null));
        ReflectionTestUtils.setField(creator, "id", 30L);
        Category category = new Category("Juridico", null);
        ReflectionTestUtils.setField(category, "id", BLOCKED_CATEGORY);
        Document document = new Document(
                "relatorio.pdf", MediaType.APPLICATION_PDF_VALUE, (long) PDF_BYTES.length,
                category, creator, status);
        document.setProcessingStatus(processingStatus);
        ReflectionTestUtils.setField(document, "id", 7L);
        given(documents.findById(7L)).willReturn(Optional.of(document));
        given(documentFiles.findByDocumentId(7L)).willReturn(Optional.of(new DocumentFile(document, PDF_BYTES)));
    }

    @Test
    @DisplayName("an approver opens a document awaiting approval, whatever its category: 200 with its bytes")
    void pendingDocumentFileIsServedToTheApprover() throws Exception {
        loggedInAs(true);
        stored(DocumentStatus.PENDING_APPROVAL, ProcessingStatus.PENDING);

        mvc.perform(get("/api/v1/documents/7/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF_BYTES));
    }

    @Test
    @DisplayName("once approved, the approver's sector rule applies again: 403 category_forbidden")
    void approvedDocumentIsBoundByTheSectorRule() throws Exception {
        loggedInAs(true);
        stored(DocumentStatus.APPROVED, ProcessingStatus.COMPLETED);

        mvc.perform(get("/api/v1/documents/7/file"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("category_forbidden"));
    }

    @Test
    @DisplayName("an employee who does not review gets 403 category_forbidden for the same pending document")
    void pendingDocumentIsRefusedToAnEmployee() throws Exception {
        loggedInAs(false);
        stored(DocumentStatus.PENDING_APPROVAL, ProcessingStatus.PENDING);

        mvc.perform(get("/api/v1/documents/7/file"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("category_forbidden"));
    }

    @Test
    @DisplayName("only indexing in flight still answers 409")
    void documentBeingIndexedIsAConflict() throws Exception {
        given(currentUser.viewer()).willReturn(new Viewer(30L, false));
        stored(DocumentStatus.APPROVED, ProcessingStatus.PROCESSING);

        // The author, so the category lets them through and the state is what answers.
        mvc.perform(get("/api/v1/documents/7/file"))
                .andExpect(status().isConflict());
    }
}
