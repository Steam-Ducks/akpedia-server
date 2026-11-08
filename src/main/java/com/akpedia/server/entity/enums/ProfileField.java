package com.akpedia.server.entity.enums;

import java.util.Locale;

/**
 * A field of the profile the user cannot change themself, only ask an admin to.
 * The literals mirror the ck_profile_change_requests_field CHECK from migration V11.
 */
public enum ProfileField {
    EMAIL,
    CPF;

    /** The name the API uses: {@code email}, {@code cpf}. */
    public String apiName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The field named so in the API, case ignored; {@code null} for any other name. */
    public static ProfileField fromApiName(String name) {
        if (name == null) {
            return null;
        }
        for (ProfileField field : values()) {
            if (field.apiName().equalsIgnoreCase(name.strip())) {
                return field;
            }
        }
        return null;
    }
}
