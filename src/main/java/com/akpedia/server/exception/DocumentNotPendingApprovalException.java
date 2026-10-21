package com.akpedia.server.exception;

import com.akpedia.server.entity.enums.DocumentStatus;

/**
 * Thrown when a document is approved or rejected while not waiting on a decision.
 *
 * <p>Carries the current status in the message, so the approver can tell whether someone else
 * already decided or the document was never submitted.
 */
public class DocumentNotPendingApprovalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentNotPendingApprovalException(Long documentId, DocumentStatus status) {
        super("O documento %d nao esta aguardando aprovacao (status: %s).".formatted(documentId, status));
    }

}
