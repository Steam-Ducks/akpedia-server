package com.akpedia.server.exception;

/** The breach notice is missing something, or has something invalid; the message says what. */
public class InvalidBreachNoticeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidBreachNoticeException(String message) {
        super(message);
    }
}
