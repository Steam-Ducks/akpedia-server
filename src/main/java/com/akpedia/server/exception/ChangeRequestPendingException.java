package com.akpedia.server.exception;

/** The user already has a pending request for that field. */
public class ChangeRequestPendingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChangeRequestPendingException(String message) {
        super(message);
    }
}
