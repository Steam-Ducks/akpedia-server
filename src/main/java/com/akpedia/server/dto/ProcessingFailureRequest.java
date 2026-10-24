package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The akpedia-ml worker could not index a document, and says why.
 *
 * @param code      stable identifier of the failure, such as {@code no_extractable_text}
 * @param message   human readable description; optional
 * @param messageId id of the Redis Stream entry, acknowledged once the failure is stored
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessingFailureRequest(
        @NotBlank String code,
        String message,
        @NotBlank @Pattern(regexp = StreamMessageIds.PATTERN) String messageId) {

    /** What goes into {@code processing_error}: the code, then the message when there is one. */
    public String describe() {
        return message == null || message.isBlank() ? code.strip() : code.strip() + ": " + message.strip();
    }
}
