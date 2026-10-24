package com.akpedia.server.service;

import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.config.DefaultUserProvider;
import com.akpedia.server.dto.ApproveDocumentRequest;
import com.akpedia.server.dto.DocumentReviewResponse;
import com.akpedia.server.entity.Document;
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
 * The approver's decision on a document waiting in the review queue: approve it, possibly
 * correcting its metadata, which sends it to be indexed; or return it to the author with a reason.
 *
 * <p>Both decisions apply only to a document in {@code PENDING_APPROVAL}, and the document is
 * locked while deciding, so a second decision on the same document is refused instead of
 * overriding the first.
 *
 * <p>The approver is the configured default user, as with the upload's author: there is no login
 * yet to say who is deciding.
 */
@Service
public class DocumentReviewService {

    private final DocumentRepository documents;
    private final DocumentFileRepository documentFiles;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final DefaultUserProvider defaultUser;
    private final ApplicationEventPublisher events;

    public DocumentReviewService(
            DocumentRepository documents,
            DocumentFileRepository documentFiles,
            CategoryRepository categories,
            UserRepository users,
            DefaultUserProvider defaultUser,
            ApplicationEventPublisher events) {
        this.documents = documents;
        this.documentFiles = documentFiles;
        this.categories = categories;
        this.users = users;
        this.defaultUser = defaultUser;
        this.events = events;
    }

    /**
     * Approves the document, applies the approver's corrections, and queues it for indexing.
     *
     * <p>The document goes to {@code processing_status: PROCESSING} here, not when the worker picks
     * it up: from this point on it is the worker's to finish. It is handed to the worker only once
     * this transaction commits; see {@link ProcessingQueue}.
     *
     * @param documentId  the document to approve
     * @param corrections metadata to change; fields left {@code null} keep their value
     * @return the document as approved
     * @throws DocumentNotFoundException           if there is no such document
     * @throws DocumentNotPendingApprovalException if it is not waiting on a decision
     * @throws CategoryNotFoundException           if the corrected category does not exist
     */
    @Transactional
    public DocumentReviewResponse approve(Long documentId, ApproveDocumentRequest corrections) {
        Document document = pendingDocument(documentId);

        if (corrections.categoryId() != null) {
            document.setCategory(categories.findById(corrections.categoryId())
                    .orElseThrow(() -> new CategoryNotFoundException(corrections.categoryId())));
        }
        if (corrections.name() != null) {
            document.setName(corrections.name().strip());
        }
        if (corrections.description() != null) {
            // Blank clears it: the upload stores no description for a blank one either, so a blank
            // here means the approver removed it, not that the document has an empty one.
            String description = corrections.description().strip();
            document.setDescription(description.isEmpty() ? null : description);
        }

        OffsetDateTime now = OffsetDateTime.now();
        document.setStatus(DocumentStatus.APPROVED);
        document.setApprover(users.getReferenceById(defaultUser.userId()));
        document.setApprovedAt(now);
        document.setReviewedAt(now);
        document.setProcessingStatus(ProcessingStatus.PROCESSING);

        events.publishEvent(new DocumentApprovedEvent(document.getId()));
        return DocumentReviewResponse.from(document);
    }

    /**
     * Returns the document to its author with the approver's reason, and discards its file.
     *
     * <p>The document row stays, so the author still sees it among their submissions, returned
     * and with the reason. The PDF goes: it is the bulk of what the document weighs, the approver
     * has already read it, and the author still holds the original they uploaded it from.
     *
     * @param documentId the document to return
     * @param comment    why it is being returned; already validated as not blank
     * @return the document as returned
     * @throws DocumentNotFoundException           if there is no such document
     * @throws DocumentNotPendingApprovalException if it is not waiting on a decision
     */
    @Transactional
    public DocumentReviewResponse reject(Long documentId, String comment) {
        Document document = pendingDocument(documentId);

        document.setStatus(DocumentStatus.REJECTED);
        document.setReviewComment(comment.strip());
        document.setReviewedAt(OffsetDateTime.now());
        documentFiles.deleteByDocumentId(documentId);

        return DocumentReviewResponse.from(document);
    }

    /**
     * Puts a document whose indexing failed back to {@code PROCESSING} and queues it again.
     *
     * <p>Locked the same way as a decision: two reprocess calls racing each other, or one racing
     * the recovery job's own redelivery, must not both see it {@code FAILED} and send it twice.
     *
     * @param documentId the failed document to try again
     * @return the document, now {@code PROCESSING}
     * @throws DocumentNotFoundException if there is no such document
     * @throws DocumentNotFailedException if its indexing did not fail
     */
    @Transactional
    public DocumentReviewResponse reprocess(Long documentId) {
        Document document = documents.findForReview(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getProcessingStatus() != ProcessingStatus.FAILED) {
            throw new DocumentNotFailedException(documentId, document.getProcessingStatus());
        }

        document.setProcessingStatus(ProcessingStatus.PROCESSING);
        document.setProcessingError(null);

        events.publishEvent(new DocumentReprocessRequestedEvent(documentId));
        return DocumentReviewResponse.from(document);
    }

    private Document pendingDocument(Long documentId) {
        Document document = documents.findForReview(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getStatus() != DocumentStatus.PENDING_APPROVAL) {
            throw new DocumentNotPendingApprovalException(documentId, document.getStatus());
        }
        return document;
    }
}
