package com.akpedia.server.exception;

/** The admin's new user is missing a field or has one Keycloak would not take. */
public class InvalidUserException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidUserException(String message) {
        super(message);
    }
}
