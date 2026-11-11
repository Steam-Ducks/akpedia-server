package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The user asking an admin to change their e-mail or CPF.
 *
 * @param field    {@code email} or {@code cpf}
 * @param newValue the value wanted; for the CPF, dots and dashes are ignored
 * @param reason   why, optional, at most 500 characters
 */
public record NewChangeRequest(
        String field,
        @JsonProperty("new_value") String newValue,
        String reason) {

    /** Without the value, so it cannot end up in a log by accident. */
    @Override
    public String toString() {
        return "NewChangeRequest[field=%s]".formatted(field);
    }
}
