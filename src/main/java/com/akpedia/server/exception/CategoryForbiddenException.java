package com.akpedia.server.exception;

/** Thrown when a document is opened by someone whose sector may not see its category. */
public class CategoryForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CategoryForbiddenException(Long documentId) {
        super("O documento %d e de uma categoria que o seu setor nao pode ver.".formatted(documentId));
    }

}
