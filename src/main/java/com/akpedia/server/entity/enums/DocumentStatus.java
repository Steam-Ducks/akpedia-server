package com.akpedia.server.entity.enums;

/**
 * Situacao do documento no fluxo de aprovacao.
 * Os literais espelham o CHECK ck_documents_status da migration V2.
 */
public enum DocumentStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    ARCHIVED
}
