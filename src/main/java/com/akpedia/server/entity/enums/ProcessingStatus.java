package com.akpedia.server.entity.enums;

/**
 * Situacao do processamento de chunks e embeddings do documento.
 * Os literais espelham o CHECK ck_documents_processing_status da migration V2.
 */
public enum ProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
