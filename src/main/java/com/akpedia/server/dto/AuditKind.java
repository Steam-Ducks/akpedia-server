package com.akpedia.server.dto;

/** Which audit entries to list: the named events, the {@code /api} requests, or both. */
public enum AuditKind {
    EVENT,
    REQUEST,
    ALL
}
