package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An enabled optional permission with the user's choice in the current version.
 *
 * @param decided {@code false} until the user chose in this version: it counts as off
 * @param change  how it differs from the previous version: {@code new}, {@code changed} or {@code null}
 */
public record UserOptionalPermission(
        String id,
        String label,
        String description,
        boolean granted,
        boolean decided,
        @JsonProperty("decided_at") OffsetDateTime decidedAt,
        String change) {
}
