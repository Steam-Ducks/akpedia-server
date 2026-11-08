package com.akpedia.server.exception;

/** The profile edit is missing a field or has an invalid one. */
public class InvalidProfileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidProfileException(String message) {
        super(message);
    }
}
