package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A new version of the terms of use, in plain text with blank lines between paragraphs. The server
 * numbers it, the next {@code X.0}.
 *
 * @param optionalPermissions what users may agree to without being required to; {@code null} for none
 */
public record PublishTermsRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 50_000) String content,
        @JsonProperty("optional_permissions") @Valid @Size(max = 50) List<OptionalPermissionDraft> optionalPermissions) {

    public PublishTermsRequest(String title, String content) {
        this(title, content, null);
    }

    public List<OptionalPermissionDraft> optionalPermissionsOrEmpty() {
        return optionalPermissions == null ? List.of() : optionalPermissions;
    }
}
