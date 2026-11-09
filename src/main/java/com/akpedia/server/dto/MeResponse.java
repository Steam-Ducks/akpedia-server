package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The signed-in user, as the app needs them right after login.
 *
 * @param id           the user's row in {@code users}
 * @param roles        their realm roles among STANDARD, APPROVER and ADMIN, as the token has them
 * @param termsPending whether they must accept the current terms before using the rest of the API
 * @param cpfProblem   {@code invalid_cpf} or {@code cpf_taken} when their account's CPF was
 *                     refused, so the rest of the API answers 422 until an admin corrects it;
 *                     {@code null} otherwise
 */
public record MeResponse(
        Long id,
        String name,
        String email,
        String sector,
        List<String> roles,
        @JsonProperty("terms_pending") boolean termsPending,
        @JsonProperty("cpf_problem") String cpfProblem) {
}
