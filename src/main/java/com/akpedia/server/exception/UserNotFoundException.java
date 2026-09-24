package com.akpedia.server.exception;

/** Thrown when a document upload names a creator that does not exist. */
public class UserNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserNotFoundException(Long userId) {
        super("Usuario %d nao encontrado.".formatted(userId));
    }

}
