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
 * @param cpf    the user's CPF, put in the token by the realm's {@code cpf} mapper; not checked
 *               here, see {@link UserProvisioningService#provision(KeycloakAccount)}
 */
public record KeycloakAccount(String id, String email, String name, String sector, String cpf) {

    /**
     * Reads the account from the token.
     *
     * @throws IncompleteAccountException if the token has no email, sector or CPF: every row in
     *                                    {@code users} needs them, and the realm requires them at
     *                                    registration, so only an account created by hand without
     *                                    them gets here
     */
    public static KeycloakAccount from(Jwt token) {
        String email = token.getClaimAsString("email");
        String sector = token.getClaimAsString("sector");
        String cpf = token.getClaimAsString("cpf");
        if (isBlank(email)) {
            throw new IncompleteAccountException("email");
        }
        if (isBlank(sector)) {
            throw new IncompleteAccountException("setor");
        }
        if (isBlank(cpf)) {
            throw new IncompleteAccountException("CPF");
        }
        return new KeycloakAccount(token.getSubject(), email.strip(), nameOf(token, email), sector.strip(),
                cpf.strip());
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
