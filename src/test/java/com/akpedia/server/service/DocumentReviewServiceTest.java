package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.akpedia.server.config.DefaultUserProvider;
import com.akpedia.server.dto.ApproveDocumentRequest;
import com.akpedia.server.dto.DocumentReviewResponse;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.DocumentNotFailedException;
import com.akpedia.server.exception.DocumentNotFoundException;
import com.akpedia.server.exception.DocumentNotPendingApprovalException;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * The approver's two decisions: what each one changes, what it leaves alone, and that neither
 * applies to a document that is not waiting on one.
 */
@ExtendWith(MockitoExtension.class)
class DocumentReviewServiceTest {

    private static final long DOCUMENT_ID = 7L;
    private static final long APPROVER_ID = 1L;

    @Mock
    private DocumentRepository documents;
    @Mock
    private DocumentFileRepository documentFiles;
    @Mock
    private CategoryRepository categories;
    @Mock
    private UserRepository users;
    @Mock
    private DefaultUserProvider defaultUser;
    @Mock
    private ApplicationEventPublisher events;

    private DocumentReviewService service;
    private Category original;
    private User approver;

    @BeforeEach
    void setUp() {
        service = new DocumentReviewService(documents, documentFiles, categories, users, defaultUser, events);
        original = category(3L, "Tecnico");
        approver = new User("Aprovador", "aprovador@akpedia.local", "hash", new Sector("Engenharia", null));
        ReflectionTestUtils.setField(approver, "id", APPROVER_ID);
    }

    private static Category category(long id, String name) {
        Category category = new Category(name, null);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    private Document stored(DocumentStatus status) {
        User author = new User("Ana", "ana@akpedia.test", "hash", new Sector("TI", null));
        Document document = new Document("manual.pdf", "application/pdf", 1024L, original, author, status);
        document.setDescription("descricao do autor");
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        given(documents.findForReview(DOCUMENT_ID)).willReturn(Optional.of(document));
        return document;
    }

    private void givenTheApprover() {
        given(defaultUser.userId()).willReturn(APPROVER_ID);
        given(users.getReferenceById(APPROVER_ID)).willReturn(approver);
    }

    // ------------------------------------------------------------------
    // approve
    // ------------------------------------------------------------------

    @Test
    @DisplayName("approving without corrections keeps the author's metadata and sends the document to processing")
    void approveWithoutCorrections() {
        Document document = stored(DocumentStatus.PENDING_APPROVAL);
        givenTheApprover();

        DocumentReviewResponse response = service.approve(DOCUMENT_ID, ApproveDocumentRequest.NONE);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(document.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(document.getApprover()).isSameAs(approver);
        assertThat(document.getApprovedAt()).isNotNull();
        assertThat(document.getReviewedAt()).isEqualTo(document.getApprovedAt());
        assertThat(document.getName()).isEqualTo("manual.pdf");
        assertThat(document.getDescription()).isEqualTo("descricao do autor");
        assertThat(document.getCategory()).isSameAs(original);
        verifyNoInteractions(categories);

        assertThat(response.status()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(response.approverId()).isEqualTo(APPROVER_ID);
        assertThat(response.reviewComment()).isNull();
    }

    @Test
    @DisplayName("approving with corrections replaces name, description and category")
    void approveWithCorrections() {
        Document document = stored(DocumentStatus.PENDING_APPROVAL);
        givenTheApprover();
        Category corrected = category(4L, "Qualidade");
        given(categories.findById(4L)).willReturn(Optional.of(corrected));

        DocumentReviewResponse response = service.approve(DOCUMENT_ID,
                new ApproveDocumentRequest("  Manual de calibracao  ", "descricao do aprovador", 4L));

        assertThat(document.getName()).isEqualTo("Manual de calibracao");
        assertThat(document.getDescription()).isEqualTo("descricao do aprovador");
        assertThat(document.getCategory()).isSameAs(corrected);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(response.categoryId()).isEqualTo(4L);
        assertThat(response.name()).isEqualTo("Manual de calibracao");
    }

    @Test
    @DisplayName("a correction can change one field and leave the others as the author sent them")
    void approveWithAPartialCorrection() {
        Document document = stored(DocumentStatus.PENDING_APPROVAL);
        givenTheApprover();

        service.approve(DOCUMENT_ID, new ApproveDocumentRequest(null, "so a descricao", null));

        assertThat(document.getDescription()).isEqualTo("so a descricao");
        assertThat(document.getName()).isEqualTo("manual.pdf");
        assertThat(document.getCategory()).isSameAs(original);
    }

    @Test
    @DisplayName("a blank description in the corrections removes the description")
    void approveWithBlankDescriptionClearsIt() {
        Document document = stored(DocumentStatus.PENDING_APPROVAL);
        givenTheApprover();

        DocumentReviewResponse response = service.approve(DOCUMENT_ID, new ApproveDocumentRequest(null, "   ", null));

        // Stored as no description, the way the upload stores a blank one -- not as "".
        assertThat(document.getDescription()).isNull();
        assertThat(response.description()).isNull();
    }

    @Test
    @DisplayName("approving publishes the event that queues the document for indexing")
    void approvePublishesTheEvent() {
        stored(DocumentStatus.PENDING_APPROVAL);
        givenTheApprover();

        service.approve(DOCUMENT_ID, ApproveDocumentRequest.NONE);

        // Only published here: it reaches Redis after commit, which ProcessingQueueTest covers.
        verify(events).publishEvent(new DocumentApprovedEvent(DOCUMENT_ID));
    }

    @Test
    @DisplayName("a corrected category that does not exist is refused, and nothing is queued")
    void approveWithAnUnknownCategory() {
        Document document = stored(DocumentStatus.PENDING_APPROVAL);
        given(categories.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(DOCUMENT_ID, new ApproveDocumentRequest(null, null, 99L)))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(events, never()).publishEvent(any());
        // The transaction rolls back on the exception; nothing was applied before the lookup anyway.
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DocumentStatus.class, names = "PENDING_APPROVAL", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a document not waiting on a decision cannot be approved")
    void approveRefusesAnyOtherStatus(DocumentStatus status) {
        Document document = stored(status);

        assertThatThrownBy(() -> service.approve(DOCUMENT_ID, ApproveDocumentRequest.NONE))
                .isInstanceOf(DocumentNotPendingApprovalException.class)
                .hasMessageContaining(status.name());

        assertThat(document.getStatus()).isEqualTo(status);
        verify(events, never()).publishEvent(any());
    }

    // ------------------------------------------------------------------
    // reject
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rejecting records the reason and when, and deletes the stored file but not the document")
    void rejectReturnsTheDocument() {
        Document document = stored(DocumentStatus.PENDING_APPROVAL);

        DocumentReviewResponse response = service.reject(DOCUMENT_ID, "  Falta a secao de revisao A.  ");

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.REJECTED);
        assertThat(document.getReviewComment()).isEqualTo("Falta a secao de revisao A.");
        assertThat(document.getReviewedAt()).isNotNull();
        verify(documentFiles).deleteByDocumentId(DOCUMENT_ID);
        verify(documents, never()).delete(any());
        verify(documents, never()).deleteById(anyLong());

        // Not an approval: no approver, not sent to processing, nothing queued.
        assertThat(document.getApprover()).isNull();
        assertThat(document.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        verifyNoInteractions(events);

        assertThat(response.status()).isEqualTo(DocumentStatus.REJECTED);
        assertThat(response.reviewComment()).isEqualTo("Falta a secao de revisao A.");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DocumentStatus.class, names = "PENDING_APPROVAL", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a document not waiting on a decision cannot be rejected, and keeps its file")
    void rejectRefusesAnyOtherStatus(DocumentStatus status) {
        Document document = stored(status);

        assertThatThrownBy(() -> service.reject(DOCUMENT_ID, "motivo"))
                .isInstanceOf(DocumentNotPendingApprovalException.class);

        assertThat(document.getStatus()).isEqualTo(status);
        verifyNoInteractions(documentFiles);
    }

    @Test
    @DisplayName("deciding on a document that does not exist answers not found")
    void unknownDocument() {
        given(documents.findForReview(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(404L, ApproveDocumentRequest.NONE))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.reject(404L, "motivo"))
                .isInstanceOf(DocumentNotFoundException.class);

        verifyNoInteractions(documentFiles, events);
    }

    // ------------------------------------------------------------------
    // reprocess
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reprocessing a failed document puts it back to PROCESSING, clears the error and queues it")
    void reprocessSendsAFailedDocumentAgain() {
        Document document = stored(DocumentStatus.APPROVED);
        document.setProcessingStatus(ProcessingStatus.FAILED);
        document.setProcessingError("no_extractable_text");

        DocumentReviewResponse response = service.reprocess(DOCUMENT_ID);

        assertThat(document.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(document.getProcessingError()).isNull();
        assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        verify(events).publishEvent(new DocumentReprocessRequestedEvent(DOCUMENT_ID));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProcessingStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a document whose indexing did not fail cannot be reprocessed")
    void reprocessRefusesAnyOtherProcessingStatus(ProcessingStatus processingStatus) {
        Document document = stored(DocumentStatus.APPROVED);
        document.setProcessingStatus(processingStatus);

        assertThatThrownBy(() -> service.reprocess(DOCUMENT_ID))
                .isInstanceOf(DocumentNotFailedException.class)
                .hasMessageContaining(processingStatus.name());

        assertThat(document.getProcessingStatus()).isEqualTo(processingStatus);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("reprocessing a document that does not exist answers not found")
    void reprocessUnknownDocument() {
        given(documents.findForReview(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reprocess(404L))
                .isInstanceOf(DocumentNotFoundException.class);

        verifyNoInteractions(events);
    }
}
