package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A choice the user just made. */
public record OptionalPermissionChoiceResponse(
        @JsonProperty("permission_id") String permissionId,
        String version,
        boolean granted,
        @JsonProperty("chosen_at") OffsetDateTime chosenAt) {
}
