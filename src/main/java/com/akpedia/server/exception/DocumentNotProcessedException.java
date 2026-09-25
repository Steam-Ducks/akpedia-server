package com.akpedia.server.exception;

import com.akpedia.server.entity.enums.ProcessingStatus;

/**
 * Thrown when the file of a document whose indexing has not settled yet is requested.
 *
 * <p>Only for {@code PENDING} and {@code PROCESSING}: both become {@code COMPLETED} on their own,
 * so the caller can retry the same request. A {@code FAILED} indexing does not raise this -- its
 * PDF is intact and gets served.
 *
 * <p>Carries the current status so the caller can tell how far along the document is.
 */
public class DocumentNotProcessedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentNotProcessedException(Long documentId, ProcessingStatus status) {
        super("O documento %d ainda esta sendo indexado pelo akpedia-ml (processing_status: %s).".formatted(documentId, status));
    }

}
