package com.akpedia.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An optional permission in a new version of the terms.
 *
 * @param id the id of a permission of the current version to keep (its choices' meaning follows it),
 *           or {@code null} for a new one, which the server names
 */
public record OptionalPermissionDraft(
        @Size(max = 100) String id,
        @NotBlank @Size(max = 100) String label,
        @NotBlank @Size(max = 500) String description) {
}
