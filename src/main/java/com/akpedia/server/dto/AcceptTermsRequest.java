package com.akpedia.server.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param version             the version the user was shown, so a newer one published meanwhile is not accepted blindly
 * @param optionalPermissions the user's choice for each optional permission of that version, by id; any left out is
 *                            not allowed
 */
public record AcceptTermsRequest(
        @NotBlank @Size(max = 20) String version,
        @JsonProperty("optional_permissions") Map<String, Boolean> optionalPermissions) {

    public AcceptTermsRequest(String version) {
        this(version, null);
    }

    public Map<String, Boolean> optionalPermissionsOrEmpty() {
        return optionalPermissions == null ? Map.of() : optionalPermissions;
    }
}
