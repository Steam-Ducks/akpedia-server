package com.akpedia.server.exception;

/** There is no entry of the terms history with that action and id. */
public class TermsHistoryEntryNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TermsHistoryEntryNotFoundException(String action, Long id) {
        super("Registro do histórico dos termos não encontrado: %s %d.".formatted(action, id));
    }
}
