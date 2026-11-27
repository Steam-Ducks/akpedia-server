package com.akpedia.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The current set, enabled and disabled, with who is opted in to each permission. */
public record AdminOptionalPermissionsResponse(
        String version,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("published_by") String publishedBy,
        List<AdminOptionalPermission> permissions) {
}
