package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of every error this API answers.
 *
 * <p>Deliberately the same shape the embedding service uses, so a refusal that comes from
 * downstream reaches the caller with its {@code code} intact instead of being flattened
 * into a generic 500.
 *
 * @param code                stable identifier of the failure
 * @param message             human readable description
 * @param supportedExtensions formats accepted, when the failure is about the file format
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        @JsonProperty("supported_extensions") List<String> supportedExtensions) {

    public ApiErrorResponse(String code, String message) {
        this(code, message, null);
    }

}
