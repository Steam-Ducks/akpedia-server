package com.akpedia.server.exception;

/** There is no such profile change request, or it is not the caller's. */
public class ChangeRequestNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChangeRequestNotFoundException(Long id) {
        super("Solicitação %d não encontrada.".formatted(id));
    }
}
