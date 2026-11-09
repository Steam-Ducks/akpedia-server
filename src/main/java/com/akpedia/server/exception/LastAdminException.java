package com.akpedia.server.exception;

/** The action would leave the realm with no enabled admin, so nobody could administer it. */
public class LastAdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The only enabled admin tried to delete their own account. */
    public LastAdminException() {
        this("Você é o único administrador ativo: dê o papel de administrador a outra pessoa antes de excluir a sua conta.");
    }

    public LastAdminException(String message) {
        super(message);
    }

    /** An admin tried to disable, or take ADMIN from, the only enabled admin. */
    public static LastAdminException ofOther() {
        return new LastAdminException(
                "Esta pessoa é o único administrador ativo: dê o papel de administrador a outra pessoa antes.");
    }
}
