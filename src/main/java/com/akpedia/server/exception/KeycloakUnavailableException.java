package com.akpedia.server.exception;

/** Keycloak's Admin API could not be reached, refused this server, or answered an error. */
public class KeycloakUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public KeycloakUnavailableException(String operation, Throwable cause) {
        super("O Keycloak nao respondeu como esperado ao %s.".formatted(operation), cause);
    }
}
