package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Which version of the terms a user accepted, and when. */
public record TermsAcceptanceResponse(
        String version,
        @JsonProperty("accepted_at") OffsetDateTime acceptedAt) {
}
