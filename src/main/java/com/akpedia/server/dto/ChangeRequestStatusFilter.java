package com.akpedia.server.dto;

/** Which change requests the admin's list shows: one status, or all of them. */
public enum ChangeRequestStatusFilter {
    PENDING,
    APPROVED,
    REJECTED,
    ALL
}
