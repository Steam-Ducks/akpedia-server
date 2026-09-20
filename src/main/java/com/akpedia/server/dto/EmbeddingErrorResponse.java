package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of every error answered by the embedding service.
 *
 * @param code                stable identifier of the failure, e.g. {@code unsupported_format}
 * @param message             human readable description
 * @param supportedExtensions formats the service handles; only sent when the failure is about the format
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingErrorResponse(
        String code,
        String message,
        @JsonProperty("supported_extensions") List<String> supportedExtensions) {
}
