package com.akpedia.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A user on the admin's users screen: their Keycloak account, roles and where they stand on the
 * terms of use.
 *
 * @param id                   the Keycloak account id
 * @param sector               the {@code sector} attribute; {@code null} if the account has none
 * @param cpf                  the {@code cpf} attribute, 11 digits; {@code null} if the account has
 *                             none. For the admin's edit form
 * @param roles                the realm roles the user has, directly or not, among STANDARD,
 *                             APPROVER and ADMIN
 * @param termsAcceptedVersion the last version of the terms they accepted, if any
 * @param currentTermsVersion  the current version (the newest published), if any
 * @param createdAt            when the account was created in Keycloak
 */
public record AdminUser(
        String id,
        String name,
        String email,
        String sector,
        String cpf,
        boolean enabled,
        List<String> roles,
        @JsonProperty("terms_accepted_version") String termsAcceptedVersion,
        @JsonProperty("current_terms_version") String currentTermsVersion,
        @JsonProperty("created_at") OffsetDateTime createdAt) {
}
