package com.akpedia.server.exception;

/**
 * The CPF of the logged-in Keycloak account cannot be taken on: its check digits are wrong, or
 * another user already has it. The user can still ask an admin for the right one.
 */
public class AccountCpfException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public AccountCpfException(String code) {
        super("invalid_cpf".equals(code)
                ? "O CPF da sua conta é inválido. Peça a correção a um administrador, informando o CPF certo."
                : "O CPF da sua conta já pertence a outro usuário. Peça a correção a um administrador, "
                        + "informando o CPF certo.");
        this.code = code;
    }

    /** The error code the screen gets: {@code invalid_cpf} or {@code cpf_taken}. */
    public String getCode() {
        return code;
    }
}
