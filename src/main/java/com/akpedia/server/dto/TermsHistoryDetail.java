package com.akpedia.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the terms history with the optional permissions of its version, as the user had
 * them right before it and right after it.
 */
public record TermsHistoryDetail(
        String action,
        Long id,
        String version,
        @JsonProperty("user_name") String userName,
        @JsonProperty("user_email") String userEmail,
        OffsetDateTime at,
        List<Permission> permissions) {

    /**
     * @param grantedBefore right before this entry; {@code null} when the user had not chosen yet
     * @param grantedAfter  right after it; the same as before for a refusal, which changes nothing
     * @param changed       the permission this {@code updated} entry turned on or off
     */
    public record Permission(
            String id,
            String label,
            String description,
            @JsonProperty("granted_before") Boolean grantedBefore,
            @JsonProperty("granted_after") Boolean grantedAfter,
            boolean changed) {
    }
}
