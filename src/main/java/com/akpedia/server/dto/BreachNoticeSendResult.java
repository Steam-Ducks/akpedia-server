package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How sending a breach notice went.
 *
 * @param id       the notice, to read it again on {@code GET /api/v1/admin/breach-notices/{id}}
 * @param total    how many users it was sent to
 * @param sent     how many e-mails the SMTP server accepted
 * @param failed   how many did not go out
 * @param failures the ones that did not go out, and why
 */
public record BreachNoticeSendResult(Long id, int total, int sent, int failed, List<Failure> failures) {

    /**
     * One e-mail that did not go out.
     *
     * @param userId the recipient's row
     * @param email  the address it was sent to
     * @param reason {@code INVALID_MESSAGE}, {@code REJECTED}, {@code AUTHENTICATION}, {@code UNAVAILABLE}
     *               or {@code FAILED}; the last two may work if sent again later
     * @param error  what the mail server or library said
     */
    public record Failure(@JsonProperty("user_id") Long userId, String email, String reason, String error) {
    }
}
