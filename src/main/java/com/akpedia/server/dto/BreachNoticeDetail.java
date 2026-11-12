package com.akpedia.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.akpedia.server.entity.enums.BreachNoticeRecipientStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A breach notice as it was sent, and how it went for each recipient.
 *
 * @param body       the text, as sent
 * @param total      how many users it was sent to
 * @param sent       how many e-mails went out
 * @param failed     how many did not
 * @param pending    how many were never tried: the send was cut short (normally 0)
 * @param recipients each recipient, in the order they were sent to
 */
public record BreachNoticeDetail(
        Long id,
        String subject,
        String body,
        @JsonProperty("sent_by_name") String sentByName,
        @JsonProperty("anpd_informed") boolean anpdInformed,
        @JsonProperty("sent_at") OffsetDateTime sentAt,
        long total,
        long sent,
        long failed,
        long pending,
        List<Recipient> recipients) {

    /**
     * One recipient.
     *
     * @param name  the user's name now ("Usuário removido" once they deleted their account)
     * @param email the address it was sent to
     * @param error why it did not go out; {@code null} unless {@code FAILED}
     */
    public record Recipient(
            @JsonProperty("user_id") Long userId,
            String name,
            String email,
            BreachNoticeRecipientStatus status,
            String error) {
    }
}
