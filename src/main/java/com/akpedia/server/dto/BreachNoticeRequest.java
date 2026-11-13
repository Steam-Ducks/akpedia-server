package com.akpedia.server.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The admin's data breach notice, to send.
 *
 * <p>Validated by {@code BreachNoticeService}, not by bean validation, so that a notice sent before
 * ANPD was informed is refused for that (422) whatever else is wrong with it.
 *
 * @param subject      the e-mail's subject, a single line; normally the template's
 * @param body         the e-mail's text, plain, with its {@code {{campo}}} fields; normally the template's
 * @param fields       the value of each field, by its name ({@code data_descoberta}, ...);
 *                     {@code nome_titular} is filled for each recipient and is not sent here
 * @param allUsers     {@code true} to send to every user; or else {@code userIds}
 * @param userIds      the users to send to, by their row id; or else {@code allUsers}
 * @param anpdInformed must be {@code true}: the notice only goes out once ANPD was informed
 */
public record BreachNoticeRequest(
        @Schema(example = "Aviso importante: incidente de segurança envolvendo seus dados no Akpedia")
        String subject,
        String body,
        @Schema(example = "{\"data_descoberta\": \"10/11/2026\", \"dados_afetados\": \"Nome, e-mail e CPF\"}")
        Map<String, String> fields,
        @JsonProperty("all_users") @JsonAlias("allUsers") Boolean allUsers,
        @JsonProperty("user_ids") @JsonAlias("userIds") List<Long> userIds,
        @JsonProperty("anpd_informed") @JsonAlias("anpdInformed") Boolean anpdInformed) {
}
