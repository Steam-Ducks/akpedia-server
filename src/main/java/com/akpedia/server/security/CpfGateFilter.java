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
import com.akpedia.server.exception.AccountCpfException;
import com.akpedia.server.exception.IncompleteAccountException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers 422 {@code invalid_cpf} or {@code cpf_taken} on every {@code /api} route while the
 * account's CPF is refused, reads included. What such a user still does stays open: who they are
 * ({@code GET /me}, which says why), the terms of use, deleting the account, and their own change
 * requests ({@code /me/profile/requests}), where they send the right CPF for an admin to approve.
 *
 * <p>Runs after {@link TermsGateFilter}: the terms come first. Not a bean, for the same reason
 * as that one.
 */
public class CpfGateFilter extends OncePerRequestFilter {

    static final String REQUESTS_PATH = "/api/v1/me/profile/requests";

    private final CurrentUser currentUser;
    private final ObjectMapper json;

    public CpfGateFilter(CurrentUser currentUser, ObjectMapper json) {
        this.currentUser = currentUser;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) {
            return true;
        }
        String method = request.getMethod();
        return (HttpMethod.GET.matches(method)
                        && (path.equals(TermsGateFilter.TERMS_PATH) || path.equals(TermsGateFilter.ME_PATH)))
                || (HttpMethod.POST.matches(method)
                        && (path.equals(TermsGateFilter.ACCEPT_PATH) || path.equals(TermsGateFilter.DECLINE_PATH)))
                || (HttpMethod.DELETE.matches(method) && path.equals(TermsGateFilter.ME_PATH))
                || path.equals(REQUESTS_PATH)
                || path.startsWith(REQUESTS_PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        CpfProblem problem = authentication != null && authentication.getPrincipal() instanceof Jwt
                ? cpfProblem()
                : null;
        if (problem != null) {
            AccountCpfException refusal = new AccountCpfException(problem.code());
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getOutputStream(), new ApiErrorResponse(refusal.getCode(), refusal.getMessage()));
            return;
        }
        chain.doFilter(request, response);
    }

    private CpfProblem cpfProblem() {
        try {
            ProvisionedUser user = currentUser.provisioned();
            return user == null ? null : user.cpfProblem();
        } catch (IncompleteAccountException e) {
            // No row to check: the routes that need the user answer 403 incomplete_account.
            return null;
        }
    }
}
