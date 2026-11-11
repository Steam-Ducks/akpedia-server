package com.akpedia.server.dto;

import java.util.List;

/**
 * A realm role as the users screen explains it.
 *
 * @param name        the role's name in Keycloak
 * @param label       its name on screen
 * @param assignable  whether the admin gives and takes it; STANDARD comes with every account
 * @param permissions what it allows, on top of the roles below it
 */
public record AdminRole(String name, String label, String description, boolean assignable, List<String> permissions) {
}
