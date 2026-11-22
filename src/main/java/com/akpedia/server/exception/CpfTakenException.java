package com.akpedia.server.exception;

/** Another user of the realm already has that CPF. */
public class CpfTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CpfTakenException() {
        super("Já existe um usuário com este CPF.");
    }
}
