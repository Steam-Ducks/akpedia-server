package com.akpedia.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An optional permission of the current terms with who allowed it (e.g. who to send product update e-mails). */
public record AdminOptionalPermission(
        String id,
        String label,
        String description,
        @JsonProperty("granted_users") List<GrantedUser> grantedUsers) {

    public record GrantedUser(
            @JsonProperty("user_id") Long userId,
            String name,
            String email,
            OffsetDateTime since) {
    }
}
