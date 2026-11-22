package com.akpedia.server.exception;

/** A role that does not exist, or that is not assigned by hand (STANDARD comes with every account). */
public class InvalidRoleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidRoleException(String role) {
        super("O papel '%s' nao pode ser atribuido: use APPROVER ou ADMIN.".formatted(role));
    }

    private InvalidRoleException(String message, boolean unused) {
        super(message);
    }

    /** A filter on the users list named a role the screen does not know. */
    public static InvalidRoleException ofFilter(String role) {
        return new InvalidRoleException(
                "Papel desconhecido no filtro: '%s'. Use STANDARD, APPROVER ou ADMIN.".formatted(role), true);
    }
}
