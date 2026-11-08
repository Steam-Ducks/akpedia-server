package com.akpedia.server.exception;

/** The only enabled admin tried to delete their account, which would leave nobody to administer. */
public class LastAdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LastAdminException() {
        super("Você é o único administrador ativo: dê o papel de administrador a outra pessoa antes de excluir a sua conta.");
    }
}
