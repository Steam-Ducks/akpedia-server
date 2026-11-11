package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The user's own profile, as Keycloak holds it, and which fields they may change.
 *
 * @param cpf         11 digits, or {@code null} if the account has none
 * @param sector      the {@code sector} attribute, or {@code null}
 * @param roles       their realm roles among STANDARD, APPROVER and ADMIN
 * @param editable    the fields the user changes themself ({@code PUT /me/profile})
 * @param requestable the fields they can only ask an admin to change ({@code POST /me/profile/requests})
 */
public record Profile(
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String email,
        String cpf,
        String sector,
        List<String> roles,
        List<String> editable,
        List<String> requestable) {

    public static final List<String> EDITABLE = List.of("first_name", "last_name", "sector");
    public static final List<String> REQUESTABLE = List.of("email", "cpf");
}
