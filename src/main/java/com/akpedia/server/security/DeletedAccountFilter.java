package com.akpedia.server.security;

import java.io.IOException;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.service.AccountDeletionLog;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers 401 {@code account_deleted} to a token of a deleted account. Such a token stays valid
 * for a few minutes after the deletion, and used it would create the user's row again.
 *
 * <p>Deleting the account again stays open, so a deletion Keycloak failed halfway can be finished.
 * Not a bean, for the same reason as {@link TermsGateFilter}.
 */
public class DeletedAccountFilter extends OncePerRequestFilter {

    private final AccountDeletionLog deletions;
    private final ObjectMapper json;

    public DeletedAccountFilter(AccountDeletionLog deletions, ObjectMapper json) {
        this.deletions = deletions;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/")
                || (HttpMethod.DELETE.matches(request.getMethod()) && path.equals(TermsGateFilter.ME_PATH));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && deletions.isDeleted(jwt.getSubject())) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getOutputStream(), new ApiErrorResponse("account_deleted",
                    "Esta conta foi excluída."));
            return;
        }
        chain.doFilter(request, response);
    }
}
