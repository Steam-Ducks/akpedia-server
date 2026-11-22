package com.akpedia.server.exception;

/** An admin tried to disable their own account. */
public class CannotDisableSelfException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CannotDisableSelfException() {
        super("Voce nao pode desativar a sua propria conta.");
    }
}
