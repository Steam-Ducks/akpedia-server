package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.akpedia.server.entity.TermsAcceptance;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Who accepted which version of the terms, and when, as the admin's list shows it. */
public record TermsAcceptanceEntry(
        Long id,
        String version,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_name") String userName,
        @JsonProperty("user_email") String userEmail,
        @JsonProperty("accepted_at") OffsetDateTime acceptedAt) {

    public static TermsAcceptanceEntry of(TermsAcceptance acceptance) {
        return new TermsAcceptanceEntry(acceptance.getId(), acceptance.getTermsVersion().getVersion(),
                acceptance.getUser().getId(), acceptance.getUser().getName(), acceptance.getUser().getEmail(),
                acceptance.getAcceptedAt());
    }
}
