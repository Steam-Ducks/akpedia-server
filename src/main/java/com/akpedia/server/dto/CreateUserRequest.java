package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A user the admin creates. Checked by the service, which answers {@code invalid_user} with what
 * is wrong. The password is never logged nor recorded.
 *
 * @param cpf    11 digits; dots and dashes are ignored
 * @param sector one of the realm's sectors
 * @param roles  APPROVER, ADMIN, both or neither; STANDARD comes with every account
 */
public record CreateUserRequest(
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String email,
        String password,
        String cpf,
        String sector,
        List<String> roles) {

    /** Without the password, so it cannot end up in a log by accident. */
    @Override
    public String toString() {
        return "CreateUserRequest[email=%s, sector=%s, roles=%s]".formatted(email, sector, roles);
    }
}
