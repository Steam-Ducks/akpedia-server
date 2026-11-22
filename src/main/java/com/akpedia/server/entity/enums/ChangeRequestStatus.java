package com.akpedia.server.entity.enums;

/**
 * Where a profile change request stands. A request is decided once, and never reopened.
 * The literals mirror the ck_profile_change_requests_status CHECK from migration V11.
 */
public enum ChangeRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}
