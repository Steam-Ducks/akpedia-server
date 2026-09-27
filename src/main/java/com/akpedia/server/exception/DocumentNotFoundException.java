package com.akpedia.server.exception;

/** Thrown when a request names a document that does not exist. */
public class DocumentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentNotFoundException(Long documentId) {
        super("Documento %d nao encontrado.".formatted(documentId));
    }

}
