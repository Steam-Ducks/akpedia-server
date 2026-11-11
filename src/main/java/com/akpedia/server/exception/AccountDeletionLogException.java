package com.akpedia.server.exception;

/** The deletion log could not be written, so the account was not deleted. */
public class AccountDeletionLogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountDeletionLogException(Throwable cause) {
        super("Não foi possível registrar a exclusão da conta; ela não foi excluída. Tente de novo mais tarde.", cause);
    }
}
