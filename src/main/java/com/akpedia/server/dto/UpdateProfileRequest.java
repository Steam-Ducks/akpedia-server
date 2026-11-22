package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the user changes of their own profile. Checked by the service, which answers
 * {@code invalid_profile} with what is wrong.
 *
 * @param sector one of the realm's sectors
 */
public record UpdateProfileRequest(
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String sector) {
}
