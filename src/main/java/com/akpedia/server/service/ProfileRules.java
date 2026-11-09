package com.akpedia.server.service;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.akpedia.server.client.KeycloakAdminClient;
import com.akpedia.server.exception.CpfTakenException;

/**
 * How the profile's fields are checked and cleaned, the same wherever they are entered: an admin
 * creating or editing a user, the user editing themself or asking for a new e-mail or CPF. Each
 * method takes the exception its caller answers with, so the screen gets that caller's code.
 */
final class ProfileRules {

    /** Keycloak's user profile caps every attribute at 255. */
    static final int MAX_LENGTH = 255;

    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final Pattern CPF = Pattern.compile("^[0-9]{11}$");

    private ProfileRules() {
    }

    /**
     * The value, trimmed.
     *
     * @param what the field, phrased to follow "Informe": "o nome"
     */
    static String required(String value, String what, Function<String, ? extends RuntimeException> invalid) {
        if (value == null || value.isBlank()) {
            throw invalid.apply("Informe %s.".formatted(what));
        }
        String stripped = value.strip();
        if (stripped.length() > MAX_LENGTH) {
            throw invalid.apply("%s deve ter no máximo %d caracteres.".formatted(capitalized(what), MAX_LENGTH));
        }
        return stripped;
    }

    /** The e-mail, trimmed and in lower case. */
    static String email(String value, Function<String, ? extends RuntimeException> invalid) {
        String email = required(value, "o e-mail", invalid).toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) {
            throw invalid.apply("Informe um e-mail válido.");
        }
        return email;
    }

    /** The CPF's 11 digits, without dots, dashes or spaces, with valid check digits. */
    static String cpf(String value, Function<String, ? extends RuntimeException> invalid) {
        String cpf = required(value, "o CPF", invalid).replaceAll("[.\\-\\s]", "");
        if (!CPF.matcher(cpf).matches()) {
            throw invalid.apply("O CPF deve ter 11 dígitos, só números.");
        }
        if (!hasValidCheckDigits(cpf)) {
            throw invalid.apply("CPF inválido: confira os dígitos.");
        }
        return cpf;
    }

    /**
     * Whether the last two digits are the CPF's check digits. A CPF of one repeated digit
     * ("11111111111") passes the arithmetic but is not issued, so it is refused too.
     *
     * @param cpf 11 digits
     */
    static boolean hasValidCheckDigits(String cpf) {
        if (cpf.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigit(cpf, 9) == cpf.charAt(9) - '0' && checkDigit(cpf, 10) == cpf.charAt(10) - '0';
    }

    /** The check digit computed from the first {@code length} digits. */
    private static int checkDigit(String cpf, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (cpf.charAt(i) - '0') * (length + 1 - i);
        }
        int rest = sum * 10 % 11;
        return rest == 10 ? 0 : rest;
    }

    /**
     * Refuses a CPF that another account of the realm has.
     *
     * @param keycloakId the account getting the CPF, {@code null} for one not created yet
     * @throws CpfTakenException if another account has it
     */
    static void ensureCpfFree(KeycloakAdminClient keycloak, String keycloakId, String cpf) {
        if (keycloak.usersWithCpf(cpf).stream().anyMatch(other -> !other.id().equals(keycloakId))) {
            throw new CpfTakenException();
        }
    }

    /** One of the realm's sectors. */
    static String sector(String value, Function<String, ? extends RuntimeException> invalid) {
        String sector = required(value, "o setor", invalid);
        if (!AdminUserService.SECTORS.contains(sector)) {
            throw invalid.apply("Setor inválido: escolha um entre %s."
                    .formatted(String.join(", ", AdminUserService.SECTORS)));
        }
        return sector;
    }

    /** "O nome" from "o nome". */
    private static String capitalized(String what) {
        return what.isEmpty() ? what : Character.toUpperCase(what.charAt(0)) + what.substring(1);
    }
}
