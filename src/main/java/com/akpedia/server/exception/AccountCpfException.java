package com.akpedia.server.exception;

/**
 * The CPF of the logged-in Keycloak account cannot be taken on: its check digits are wrong, or
 * another user already has it. Answered on the user's first access, or after the CPF changed.
 */
public class AccountCpfException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    private AccountCpfException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static AccountCpfException invalid() {
        return new AccountCpfException("invalid_cpf",
                "O CPF da sua conta é inválido; peça a um administrador para corrigi-lo.");
    }

    public static AccountCpfException taken() {
        return new AccountCpfException("cpf_taken",
                "O CPF da sua conta já pertence a outro usuário; peça a um administrador para corrigi-lo.");
    }

    /** The error code the screen gets: {@code invalid_cpf} or {@code cpf_taken}. */
    public String getCode() {
        return code;
    }
}
