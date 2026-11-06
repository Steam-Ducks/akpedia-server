package com.akpedia.server.security;

import org.springframework.security.oauth2.jwt.Jwt;

import com.akpedia.server.exception.IncompleteAccountException;

/**
 * The Keycloak account a request's access token belongs to.
 *
 * @param id     the account id, the token's {@code sub}
 * @param email  the account's email, which is also its login
 * @param name   the full name, as the user registered it
 * @param sector the user's sector, put in the token by the realm's {@code sector} mapper
 */
public record KeycloakAccount(String id, String email, String name, String sector) {

    /**
     * Reads the account from the token.
     *
     * @throws IncompleteAccountException if the token has no email or no sector: every row in
     *                                    {@code users} needs both, and the realm requires them at
     *                                    registration, so only an account created by hand without
     *                                    them gets here
     */
    public static KeycloakAccount from(Jwt token) {
        String email = token.getClaimAsString("email");
        String sector = token.getClaimAsString("sector");
        if (isBlank(email)) {
            throw new IncompleteAccountException("email");
        }
        if (isBlank(sector)) {
            throw new IncompleteAccountException("setor");
        }
        return new KeycloakAccount(token.getSubject(), email.strip(), nameOf(token, email), sector.strip());
    }

    private static String nameOf(Jwt token, String email) {
        String name = token.getClaimAsString("name");
        if (!isBlank(name)) {
            return name.strip();
        }
        String given = token.getClaimAsString("given_name");
        String family = token.getClaimAsString("family_name");
        String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).strip();
        return joined.isEmpty() ? email : joined;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
