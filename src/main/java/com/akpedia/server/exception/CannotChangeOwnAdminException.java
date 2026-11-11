package com.akpedia.server.exception;

/** An admin tried to take the ADMIN role away from themself, which could leave nobody to give it back. */
public class CannotChangeOwnAdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CannotChangeOwnAdminException() {
        super("Voce nao pode remover o seu proprio papel de administrador.");
    }
}
