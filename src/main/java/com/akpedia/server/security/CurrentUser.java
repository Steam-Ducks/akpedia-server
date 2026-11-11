package com.akpedia.server.security;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.akpedia.server.exception.AccountCpfException;

/** The user behind the current request, as a row in {@code users}. */
@Component
public class CurrentUser {

    private final UserProvisioningService provisioning;

    public CurrentUser(UserProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    /**
     * Id of the logged-in user, creating their row on their first request.
     *
     * @throws IllegalStateException if the request carries no access token, which
     *         {@code SecurityConfig} already refuses on every route that gets here
     * @throws com.akpedia.server.exception.IncompleteAccountException if the account has no email,
     *         sector or CPF
     * @throws AccountCpfException if the account's CPF is invalid or another user's; see
     *         {@link #provisioned()} for the routes that let such a user in
     */
    public Long id() {
        ProvisionedUser user = provisioned();
        if (user.cpfProblem() != null) {
            throw new AccountCpfException(user.cpfProblem().code());
        }
        return user.id();
    }

    /**
     * The logged-in user's row, even when their CPF could not be taken on. Only for what such a
     * user still does: see who they are, accept the terms, and ask an admin for the right CPF.
     */
    public ProvisionedUser provisioned() {
        KeycloakAccount account = KeycloakAccount.from(token());
        try {
            return provisioning.provision(account);
        } catch (DataIntegrityViolationException e) {
            // Right after login the screen fires several requests at once, and another one created
            // the row (or the sector) first. It is there now.
            return provisioning.provision(account);
        }
    }

    /** The Keycloak account id (the token's {@code sub}), without creating the user's row. */
    public String keycloakId() {
        return token().getSubject();
    }

    private static Jwt token() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No access token on this request.");
        }
        return jwt;
    }
}
