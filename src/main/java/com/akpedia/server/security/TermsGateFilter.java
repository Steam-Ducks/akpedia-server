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
import com.akpedia.server.service.TermsService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers 403 {@code terms_pending} on every {@code /api} route until the user accepted the
 * current terms of use. The routes the terms screen needs stay open: who the user is
 * ({@code GET /me}, which says the terms are pending), reading the terms, and accepting or
 * declining them. So does deleting one's account: a user may leave without
 * accepting.
 *
 * <p>Runs inside the security chain, after the token was checked, so a request without one still
 * gets its 401. Not a bean on purpose: as one, Spring Boot would also register it as a filter of
 * its own, outside the chain.
 */
public class TermsGateFilter extends OncePerRequestFilter {

    static final String TERMS_PATH = "/api/v1/terms";
    static final String ACCEPT_PATH = "/api/v1/terms/accept";
    static final String DECLINE_PATH = "/api/v1/terms/decline";
    static final String ME_PATH = "/api/v1/me";

    private final TermsService terms;
    private final ObjectMapper json;

    public TermsGateFilter(TermsService terms, ObjectMapper json) {
        this.terms = terms;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) {
            return true;
        }
        String method = request.getMethod();
        return (HttpMethod.GET.matches(method) && (path.equals(TERMS_PATH) || path.equals(ME_PATH)))
                || (HttpMethod.POST.matches(method) && (path.equals(ACCEPT_PATH) || path.equals(DECLINE_PATH)))
                || (HttpMethod.DELETE.matches(method) && path.equals(ME_PATH));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && terms.mustAccept(jwt.getSubject())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getOutputStream(), new ApiErrorResponse("terms_pending",
                    "Aceite os termos de uso atuais para continuar usando o Akpedia."));
            return;
        }
        chain.doFilter(request, response);
    }
}
