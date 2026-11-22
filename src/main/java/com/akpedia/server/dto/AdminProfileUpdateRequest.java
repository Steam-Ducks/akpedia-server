package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An admin's edit of a user's profile: every field, e-mail and CPF included. Checked by the
 * service, which answers {@code invalid_profile} with what is wrong.
 *
 * @param cpf    11 digits; dots and dashes are ignored
 * @param sector one of the realm's sectors
 */
public record AdminProfileUpdateRequest(
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String email,
        String cpf,
        String sector) {

    /** Without the e-mail and CPF, so they cannot end up in a log by accident. */
    @Override
    public String toString() {
        return "AdminProfileUpdateRequest[sector=%s]".formatted(sector);
    }
}
