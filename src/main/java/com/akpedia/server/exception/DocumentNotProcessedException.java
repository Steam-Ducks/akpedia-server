package com.akpedia.server.exception;

import com.akpedia.server.entity.enums.ProcessingStatus;

/**
 * Thrown when the file of a document akpedia-ml is indexing right now is requested.
 *
 * <p>Only for {@code PROCESSING}: it settles on its own, so the caller can retry the same request.
 * A document still {@code PENDING} (awaiting approval) or whose indexing {@code FAILED} does not
 * raise this -- its PDF is intact and gets served.
 *
 * <p>Carries the current status so the caller can tell how far along the document is.
 */
public class DocumentNotProcessedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentNotProcessedException(Long documentId, ProcessingStatus status) {
        super("O documento %d ainda esta sendo indexado pelo akpedia-ml (processing_status: %s).".formatted(documentId, status));
    }

}
