package com.akpedia.server.exception;

/** The user accepted a version of the terms that is no longer the current one. */
public class TermsChangedException extends RuntimeException {

    public TermsChangedException(int accepted, Integer current) {
        super(current == null
                ? "Nao ha termos de uso publicados para aceitar."
                : "Os termos de uso mudaram: a versao atual e a %d, nao a %d.".formatted(current, accepted));
    }
}
