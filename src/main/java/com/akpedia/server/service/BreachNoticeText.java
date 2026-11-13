package com.akpedia.server.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.akpedia.server.exception.InvalidBreachNoticeException;

/**
 * Fills the {@code {{ }}} fields of the data breach notice, as the notice document says.
 *
 * <ul>
 *   <li>The admin's values go in at send time; {@code {{nome_titular}}} is left for
 *       {@link #personalize}, which writes in each recipient's name.</li>
 *   <li>An optional field left blank takes its whole line out ("campos opcionais vazios devem ter
 *       seu parágrafo ou linha removidos"), and the blank lines that leaves become one.</li>
 *   <li>A required field left blank, a field the template does not have, or one the admin does not
 *       fill is refused, naming each one -- a notice with a {@code {{ }}} still in it never goes out.</li>
 * </ul>
 *
 * <p>The e-mail is plain text, so values are written in as they are; they may span lines, as the
 * affected data does ("um por linha"), but never carry braces of their own.
 */
final class BreachNoticeText {

    /** {@code {{campo}}}, spaces inside the braces allowed. */
    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private static final String RECIPIENT_NAME = "{{" + BreachNoticeField.NOME_TITULAR.key() + "}}";

    private BreachNoticeText() {
    }

    /**
     * The admin's values, checked: only fields the template has, none of the automatic ones, and no
     * braces in them. Keys are matched as written, blank values count as not given.
     *
     * @throws InvalidBreachNoticeException naming what is wrong
     */
    static Map<BreachNoticeField, String> values(Map<String, String> given) {
        Map<BreachNoticeField, String> values = new EnumMap<>(BreachNoticeField.class);
        if (given == null) {
            return values;
        }
        Set<String> unknown = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : given.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().strip();
            Optional<BreachNoticeField> field = BreachNoticeField.byKey(key);
            if (field.isEmpty()) {
                unknown.add(key);
                continue;
            }
            if (field.get().automatic()) {
                throw new InvalidBreachNoticeException(
                        "fields.%s e preenchido automaticamente, com o nome de cada destinatario.".formatted(key));
            }
            String value = entry.getValue() == null ? "" : entry.getValue().strip();
            if (value.contains("{{") || value.contains("}}")) {
                throw new InvalidBreachNoticeException("fields.%s nao pode ter {{ ou }}.".formatted(key));
            }
            if (!value.isEmpty()) {
                values.put(field.get(), value.replace("\r\n", "\n"));
            }
        }
        if (!unknown.isEmpty()) {
            throw new InvalidBreachNoticeException("Campos que o modelo nao tem: %s.".formatted(String.join(", ", unknown)));
        }
        return values;
    }

    /**
     * The text with the admin's values in, {@code {{nome_titular}}} kept for {@link #personalize}.
     *
     * @param what  {@code subject} or {@code body}, to name it in the refusal
     * @throws InvalidBreachNoticeException if a required field is blank or a field is unknown
     */
    static String fill(String text, Map<BreachNoticeField, String> values, String what) {
        Set<String> missing = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();

        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            Matcher matcher = FIELD.matcher(line);
            StringBuilder filled = new StringBuilder();
            boolean dropLine = false;
            while (matcher.find()) {
                String key = matcher.group(1);
                Optional<BreachNoticeField> field = BreachNoticeField.byKey(key);
                String replacement;
                if (field.isEmpty()) {
                    unknown.add(key);
                    replacement = matcher.group();
                } else if (field.get().automatic()) {
                    replacement = "{{" + key + "}}";
                } else if (values.containsKey(field.get())) {
                    replacement = values.get(field.get());
                    // A list, one item per line ("um por linha"), starts on a line of its own rather
                    // than glued to the text before it ("são: Nome" over "E-mail").
                    if (replacement.contains("\n") && !line.substring(0, matcher.start()).isBlank()) {
                        replacement = "\n" + replacement;
                    }
                } else if (field.get().required()) {
                    missing.add(key);
                    replacement = matcher.group();
                } else {
                    dropLine = true;
                    replacement = "";
                }
                matcher.appendReplacement(filled, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(filled);
            if (!dropLine) {
                lines.add(filled.toString());
            }
        }

        if (!unknown.isEmpty()) {
            throw new InvalidBreachNoticeException(
                    "%s tem campos que o modelo nao tem: %s.".formatted(what, String.join(", ", unknown)));
        }
        if (!missing.isEmpty()) {
            throw new InvalidBreachNoticeException(
                    "Campos obrigatorios em branco: %s.".formatted(String.join(", ", missing)));
        }
        return String.join("\n", lines).replaceAll("[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
    }

    /**
     * Refuses a text that lost a field the law asks the notice to carry.
     *
     * <p>The admin may reword the text around the fields, but every required one has to stay: each
     * stands for an item of art. 9 of Resolução CD/ANPD nº 15/2024 (what happened, which data, the
     * protection measures, the date ANPD was told, the measures taken, the DPO's contact), and
     * {@code {{nome_titular}}} for the direct, individual notice of its § 1º.
     *
     * @param texts the subject and the body, as the admin sent them
     * @throws InvalidBreachNoticeException naming every required field missing from both
     */
    static void requireFields(String... texts) {
        Set<String> present = new LinkedHashSet<>();
        for (String text : texts) {
            Matcher matcher = FIELD.matcher(text == null ? "" : text);
            while (matcher.find()) {
                present.add(matcher.group(1));
            }
        }
        List<String> missing = java.util.Arrays.stream(BreachNoticeField.values())
                .filter(BreachNoticeField::required)
                .map(BreachNoticeField::key)
                .filter(key -> !present.contains(key))
                .toList();
        if (!missing.isEmpty()) {
            throw new InvalidBreachNoticeException(
                    "O texto precisa manter os campos obrigatorios: %s.".formatted(String.join(", ", missing)));
        }
    }

    /** The filled text for one recipient: their name where {@code {{nome_titular}}} is. */
    static String personalize(String filled, String recipientName) {
        return filled.replace(RECIPIENT_NAME, recipientName);
    }
}
