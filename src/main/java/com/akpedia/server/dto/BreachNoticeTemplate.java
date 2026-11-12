package com.akpedia.server.dto;

/**
 * The suggested text of a data breach notice, for the admin to complete before sending.
 *
 * @param subject the suggested subject
 * @param body    the suggested text, with {@code [PREENCHER: ...]} where the admin has to write
 *                what is particular to this incident; a notice still holding one is refused
 */
public record BreachNoticeTemplate(String subject, String body) {
}
