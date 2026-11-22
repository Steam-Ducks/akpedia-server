package com.akpedia.server.exception;

/** The realm already has a user with that email. */
public class UserExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserExistsException(String email) {
        super("Já existe um usuário com o e-mail %s.".formatted(email));
    }
}
