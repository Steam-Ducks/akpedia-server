package com.akpedia.server.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A realm role as the Admin API names it. Adding or removing a mapping takes both fields, and the
 * service account cannot read roles by name, so the id comes from the user's own mappings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakRole(String id, String name) {
}
