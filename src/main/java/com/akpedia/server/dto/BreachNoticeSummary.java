package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One breach notice in the admin's list.
 *
 * @param sentByName the admin who sent it
 * @param total      how many users it was sent to
 * @param sent       how many e-mails went out
 * @param failed     how many did not
 * @param pending    how many were never tried: the send was cut short (normally 0)
 */
public record BreachNoticeSummary(
        Long id,
        String subject,
        @JsonProperty("sent_by_name") String sentByName,
        @JsonProperty("anpd_informed") boolean anpdInformed,
        @JsonProperty("sent_at") OffsetDateTime sentAt,
        long total,
        long sent,
        long failed,
        long pending) {
}
