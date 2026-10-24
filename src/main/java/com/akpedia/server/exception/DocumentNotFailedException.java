package com.akpedia.server.exception;

import com.akpedia.server.entity.enums.ProcessingStatus;

/**
 * Thrown when a document is asked to be reprocessed while its indexing did not fail: there is
 * nothing to recover from, and republishing it would race whatever is already handling it.
 */
public class DocumentNotFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentNotFailedException(Long documentId, ProcessingStatus processingStatus) {
        super("O documento %d nao esta com falha de processamento (processing_status: %s)."
                .formatted(documentId, processingStatus));
    }

}
