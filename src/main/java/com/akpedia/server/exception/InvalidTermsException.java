package com.akpedia.server.exception;

/** The admin's new version of the terms has a field the server does not take. */
public class InvalidTermsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidTermsException(String message) {
        super(message);
    }
}
