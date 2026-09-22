package com.akpedia.server.exception;

/** Thrown when a document upload is refused before any conversion or lookup is attempted. */
public class InvalidDocumentUploadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidDocumentUploadException(String message) {
        super(message);
    }

}
