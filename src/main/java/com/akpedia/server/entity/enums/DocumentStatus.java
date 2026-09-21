package com.akpedia.server.entity.enums;

/**
 * Document state in the approval flow.
 * The literals mirror the ck_documents_status CHECK from migration V2.
 */
public enum DocumentStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    ARCHIVED
}
