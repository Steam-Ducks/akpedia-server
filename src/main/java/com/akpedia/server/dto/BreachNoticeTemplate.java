package com.akpedia.server.dto;

import java.util.List;

/**
 * The data breach notice template, as the notice document defines it, for the admin to fill.
 *
 * @param subject the subject
 * @param body    the text, plain, with {@code {{campo}}} where each field goes
 * @param fields  every field the text has, in the document's order
 */
public record BreachNoticeTemplate(String subject, String body, List<Field> fields) {

    /**
     * One {@code {{ }}} field.
     *
     * @param name        the name between the braces, and the key to send it under in {@code fields}
     * @param required    whether the notice can only go out with it filled; an optional one left
     *                    blank takes its line out of the e-mail
     * @param automatic   {@code true} for the one the server fills for each recipient
     *                    ({@code nome_titular}); never sent by the admin
     * @param type        {@code TEXT}, {@code MULTILINE}, {@code DATE} ({@code 2026-11-10}) or
     *                    {@code DATE_RANGE} ({@code 2026-11-08} or {@code 2026-11-08/2026-11-09})
     * @param description what goes in it
     * @param example     an illustration of a value as it is sent, never sent itself
     */
    public record Field(String name, boolean required, boolean automatic, String type, String description,
            String example) {
    }
}
