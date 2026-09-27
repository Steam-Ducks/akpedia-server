package com.akpedia.server.exception;

/** The search request cannot be executed because one of its parameters is invalid. */
public class InvalidSearchRequestException extends RuntimeException {

    public InvalidSearchRequestException(String message) {
        super(message);
    }
}
