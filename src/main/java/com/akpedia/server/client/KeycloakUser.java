package com.akpedia.server.client;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A user as Keycloak's Admin API describes them; only the fields the users screen needs.
 *
 * @param createdTimestamp when the account was created, in epoch milliseconds
 * @param attributes       the user profile's custom attributes, {@code sector} among them
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakUser(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        Boolean enabled,
        Long createdTimestamp,
        Map<String, List<String>> attributes) {

    /** First and last name, or the email when the account has neither. */
    public String name() {
        String joined = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).strip();
        if (!joined.isEmpty()) {
            return joined;
        }
        return email != null ? email : username;
    }

    public String attribute(String name) {
        List<String> values = attributes == null ? null : attributes.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
