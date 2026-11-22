package com.akpedia.server.exception;

/** Thrown when the logged-in Keycloak account lacks something every user here needs. */
public class IncompleteAccountException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IncompleteAccountException(String missing) {
        super("A conta do Keycloak nao tem %s; complete o cadastro para usar o Akpedia.".formatted(missing));
    }
}
