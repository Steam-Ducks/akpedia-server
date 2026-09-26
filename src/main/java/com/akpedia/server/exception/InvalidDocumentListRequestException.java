package com.akpedia.server.exception;

/** The document list cannot be answered because one of its parameters is invalid. */
public class InvalidDocumentListRequestException extends RuntimeException {

    public InvalidDocumentListRequestException(String message) {
        super(message);
    }
}
