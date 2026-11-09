package com.akpedia.server.security;

/**
 * The user's row, after it was brought in step with their Keycloak account.
 *
 * @param id         the row's id
 * @param cpfProblem why the account's CPF was not taken on, {@code null} when it was
 */
public record ProvisionedUser(Long id, CpfProblem cpfProblem) {
}
