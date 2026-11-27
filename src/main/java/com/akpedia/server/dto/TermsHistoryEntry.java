package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One thing a user did with a version of the terms, as the admin's history shows it.
 *
 * @param action {@code accepted} (a {@code terms_acceptances} row), {@code updated} (an optional
 *               permission turned on or off later, an audit entry) or {@code declined} (an audit entry)
 * @param id     the id of that row or audit entry: unique only together with {@code action}
 * @param userId {@code null} for a refusal by someone who never had a user row
 */
public record TermsHistoryEntry(
        String action,
        Long id,
        String version,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_name") String userName,
        @JsonProperty("user_email") String userEmail,
        OffsetDateTime at) {

    public static final String ACCEPTED = "accepted";
    public static final String UPDATED = "updated";
    public static final String DECLINED = "declined";
}
