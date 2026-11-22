package com.akpedia.server.exception;

/** There is no breach notice with that id. */
public class BreachNoticeNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BreachNoticeNotFoundException(Long id) {
        super("Aviso de vazamento %d nao encontrado.".formatted(id));
    }
}
