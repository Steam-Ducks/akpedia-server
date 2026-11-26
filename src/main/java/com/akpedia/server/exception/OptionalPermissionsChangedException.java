package com.akpedia.server.exception;

/** The user chose for a version of the terms that is no longer the current one. */
public class OptionalPermissionsChangedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OptionalPermissionsChangedException(String current) {
        super(current == null
                ? "Não há termos de uso publicados."
                : "Os termos de uso mudaram: a versão atual é a %s.".formatted(current));
    }
}
