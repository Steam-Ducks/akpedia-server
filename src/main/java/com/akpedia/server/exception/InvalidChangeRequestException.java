package com.akpedia.server.exception;

/** The e-mail or CPF change request is invalid: a bad value, the current one, or a reason too long. */
public class InvalidChangeRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidChangeRequestException(String message) {
        super(message);
    }
}
