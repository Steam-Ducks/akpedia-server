package com.akpedia.server.exception;

import com.akpedia.server.entity.enums.DocumentStatus;

/**
 * Thrown when the worker sends the embeddings of a document that is not approved: storing them
 * would put a document in search that no approver released.
 */
public class DocumentNotApprovedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentNotApprovedException(Long documentId, DocumentStatus status) {
        super("O documento %d nao esta aprovado (status: %s).".formatted(documentId, status));
    }

}
