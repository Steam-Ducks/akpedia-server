package com.akpedia.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A new version of the terms of use, in plain text with blank lines between paragraphs.
 *
 * @param version what the admin calls this version, such as {@code 1.0} or {@code 2026.1}:
 *                trimmed, 1 to 20 letters, digits, {@code .}, {@code -} or {@code _}, and not
 *                one published before, in any case. Checked by the service, so the refusal
 *                says what is accepted
 */
public record PublishTermsRequest(
        String version,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 50_000) String content) {
}
