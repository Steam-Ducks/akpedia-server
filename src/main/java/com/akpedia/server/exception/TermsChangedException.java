package com.akpedia.server.exception;

/** The user accepted a version of the terms that is no longer the current one. */
public class TermsChangedException extends RuntimeException {

    public TermsChangedException(String accepted, String current) {
        super(current == null
                ? "Nao ha termos de uso publicados para aceitar."
                : "Os termos de uso mudaram: a versao atual e a %s, nao a %s.".formatted(current, accepted));
    }
}
