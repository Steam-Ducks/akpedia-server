package com.akpedia.server.security;

/** What is wrong with the CPF on a user's Keycloak account, which keeps it off their row. */
public enum CpfProblem {

    /** The check digits are wrong. */
    INVALID("invalid_cpf"),

    /** Another user's row has it. */
    TAKEN("cpf_taken");

    private final String code;

    CpfProblem(String code) {
        this.code = code;
    }

    /** The API's name for it: the error code, and {@code cpf_problem} in {@code GET /me}. */
    public String code() {
        return code;
    }
}
