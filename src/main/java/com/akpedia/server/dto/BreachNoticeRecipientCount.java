package com.akpedia.server.dto;

/**
 * How many users a notice to everyone ({@code all_users: true}) would reach right now.
 *
 * @param count users linked to a Keycloak account and not deleted, disabled ones included
 */
public record BreachNoticeRecipientCount(long count) {
}
