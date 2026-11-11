package com.akpedia.server.exception;

/** The request was already decided. */
public class ChangeRequestNotPendingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChangeRequestNotPendingException(String message) {
        super(message);
    }
}
