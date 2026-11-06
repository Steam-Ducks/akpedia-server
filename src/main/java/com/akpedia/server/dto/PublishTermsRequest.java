package com.akpedia.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new version of the terms of use, in plain text with blank lines between paragraphs. */
public record PublishTermsRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 50_000) String content) {
}
