package com.akpedia.server.dto;

/**
 * What the terms screen needs: the current terms and the last version this user accepted.
 * The user must accept when {@code current} is set and {@code accepted} is not that version.
 *
 * @param current  the current terms, or {@code null} if none were published yet
 * @param accepted the user's latest acceptance, or {@code null} if they never accepted any
 */
public record TermsStatusResponse(TermsVersionResponse current, TermsAcceptanceResponse accepted) {
}
