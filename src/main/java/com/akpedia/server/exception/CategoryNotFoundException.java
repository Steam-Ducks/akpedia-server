package com.akpedia.server.exception;

/** Thrown when a document upload names a category that does not exist. */
public class CategoryNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CategoryNotFoundException(Long categoryId) {
        super("Categoria %d nao encontrada.".formatted(categoryId));
    }

}
