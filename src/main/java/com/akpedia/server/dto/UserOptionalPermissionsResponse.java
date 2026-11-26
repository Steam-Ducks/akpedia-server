package com.akpedia.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The current version of the optional permissions and the user's choice for each enabled one. */
public record UserOptionalPermissionsResponse(
        String version,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        List<UserOptionalPermission> permissions) {
}
