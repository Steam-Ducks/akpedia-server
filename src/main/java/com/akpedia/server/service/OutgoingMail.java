package com.akpedia.server.service;

/**
 * One plain-text e-mail to one person.
 *
 * @param to      recipient address
 * @param subject subject line
 * @param body    plain text; sent as UTF-8, so accents arrive as written
 */
public record OutgoingMail(String to, String subject, String body) {
}
