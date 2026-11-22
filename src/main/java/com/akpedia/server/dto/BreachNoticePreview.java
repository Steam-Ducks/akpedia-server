package com.akpedia.server.dto;

/**
 * A breach notice e-mail as a recipient will get it, before anything is sent.
 *
 * @param subject the subject, filled
 * @param body    the text, filled, with the admin's own name where each recipient's goes
 */
public record BreachNoticePreview(String subject, String body) {
}
