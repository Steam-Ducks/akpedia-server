package com.akpedia.server.exception;

/** The edit would leave everything as it is. */
public class NothingChangedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NothingChangedException(String message) {
        super(message);
    }
}
