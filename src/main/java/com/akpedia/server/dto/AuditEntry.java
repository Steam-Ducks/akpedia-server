package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.entity.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One line of the audit screen.
 *
 * @param userEmail  who did it, as they were then; {@code null} for a request without a valid token
 * @param userName   their name as it is now, when they have a row in {@code users}; {@code null}
 *                   once they deleted their account
 * @param userPseudonym for a deleted account, the label that stands in for it, the same on all
 *                   its entries: "Usuário removido #" and the id of its anonymized row, or, for an
 *                   account that never had a row, the start of its Keycloak id; otherwise
 *                   {@code null}
 * @param details    what the event or request carried, as recorded
 * @param statusCode the answer's status; only requests have one
 */
public record AuditEntry(
        Long id,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        String action,
        @JsonProperty("user_email") String userEmail,
        @JsonProperty("user_name") String userName,
        @JsonProperty("user_pseudonym") String userPseudonym,
        @JsonProperty("entity_type") String entityType,
        @JsonProperty("entity_id") Long entityId,
        JsonNode details,
        @JsonProperty("ip_address") String ipAddress,
        @JsonProperty("user_agent") String userAgent,
        @JsonProperty("http_method") String httpMethod,
        String path,
        @JsonProperty("status_code") Integer statusCode,
        @JsonProperty("duration_ms") Integer durationMs) {

    /** What a deleted account is shown as, followed by the id of its row. */
    public static final String PSEUDONYM_PREFIX = User.DELETED_NAME + " #";

    /** How much of the Keycloak id stands in for a deleted account that never had a row. */
    static final int KEYCLOAK_ID_PREFIX = 8;

    /**
     * @param deletedAccount whether the entry's Keycloak account was deleted; only looked at when
     *                       the entry has no row, a deleted row says so itself
     */
    public static AuditEntry of(AuditLog log, JsonNode details, boolean deletedAccount) {
        User user = log.getUser();
        boolean deleted = user != null ? user.getDeletedAt() != null : deletedAccount;
        String pseudonym = null;
        if (deleted) {
            String keycloakId = log.getUserKeycloakId();
            pseudonym = PSEUDONYM_PREFIX + (user != null
                    ? user.getId().toString()
                    : keycloakId.substring(0, Math.min(KEYCLOAK_ID_PREFIX, keycloakId.length())));
        }
        return new AuditEntry(
                log.getId(), log.getCreatedAt(), log.getAction(), deleted ? null : log.getUserEmail(),
                user == null || deleted ? null : user.getName(),
                pseudonym,
                log.getEntityType(), log.getEntityId(), details, log.getIpAddress(), log.getUserAgent(),
                log.getHttpMethod(), log.getPath(), log.getStatusCode(), log.getDurationMs());
    }
}
