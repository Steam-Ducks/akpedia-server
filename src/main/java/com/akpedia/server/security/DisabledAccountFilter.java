package com.akpedia.server.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers 401 {@code account_disabled} to a token of an account an admin disabled. Keycloak
 * refuses the account's logins and refreshes at once, but a token it already issued stays valid
 * for a few minutes; the row's {@code is_active} closes that gap.
 *
 * <p>Not a bean, for the same reason as {@link TermsGateFilter}.
 */
public class DisabledAccountFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final ObjectMapper json;

    public DisabledAccountFilter(UserRepository users, ObjectMapper json) {
        this.users = users;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && users.existsByKeycloakIdAndActiveFalse(jwt.getSubject())) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getOutputStream(), new ApiErrorResponse("account_disabled",
                    "Esta conta foi desativada por um administrador."));
            return;
        }
        chain.doFilter(request, response);
    }
}
