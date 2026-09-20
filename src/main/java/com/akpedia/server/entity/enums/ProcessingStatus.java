package com.akpedia.server.entity.enums;

/**
 * State of the chunk and embedding processing of a document.
 * The literals mirror the ck_documents_processing_status CHECK from migration V2.
 */
public enum ProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
