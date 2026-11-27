package com.akpedia.server.exception;

/** The optional permissions of a new version of the terms are invalid (an unknown or repeated id). */
public class InvalidOptionalPermissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidOptionalPermissionException(String message) {
        super(message);
    }
}
