package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.akpedia.server.entity.ProfileChangeRequest;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.ChangeRequestStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A request to change the user's e-mail or CPF, as the user and the admins see it.
 *
 * @param field         {@code email} or {@code cpf}
 * @param decisionNote  the admin's note on a rejection, if any
 * @param decidedByName the admin who decided, or {@code null} while pending (or if their
 *                      account was deleted since)
 * @param user          who asked
 */
public record ChangeRequest(
        Long id,
        String field,
        @JsonProperty("new_value") String newValue,
        String reason,
        ChangeRequestStatus status,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("decided_at") OffsetDateTime decidedAt,
        @JsonProperty("decision_note") String decisionNote,
        @JsonProperty("decided_by_name") String decidedByName,
        Requester user) {

    /**
     * Who asked.
     *
     * @param id the Keycloak account id, {@code null} if the row is no longer linked to one
     */
    public record Requester(String id, String name, String email) {
    }

    public static ChangeRequest of(ProfileChangeRequest request) {
        User user = request.getUser();
        User decidedBy = request.getDecidedBy();
        return new ChangeRequest(request.getId(), request.getField().apiName(), request.getNewValue(),
                request.getReason(), request.getStatus(), request.getCreatedAt(), request.getDecidedAt(),
                request.getDecisionNote(), decidedBy == null ? null : decidedBy.getName(),
                new Requester(user.getKeycloakId(), user.getName(), user.getEmail()));
    }
}
