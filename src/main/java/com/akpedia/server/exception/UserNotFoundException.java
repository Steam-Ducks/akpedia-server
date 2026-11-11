package com.akpedia.server.exception;

/** Thrown when a document upload names a creator, or the admin a Keycloak account, that does not exist. */
public class UserNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserNotFoundException(Long userId) {
        super("Usuario %d nao encontrado.".formatted(userId));
    }

    public UserNotFoundException(String keycloakId) {
        super("Usuario %s nao encontrado.".formatted(keycloakId));
    }

}
