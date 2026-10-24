package com.akpedia.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.akpedia.server.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Lets a request through to the {@code /internal} routes only when its {@code X-Internal-Token}
 * header matches {@code INTERNAL_API_TOKEN}; anything else answers 401 before reaching a
 * controller.
 *
 * <p>Not a component: {@link InternalApiConfig} registers it for {@code /internal/*} only, so the
 * servlet container decides which paths it guards from the normalized path, and a request such as
 * {@code /api/../internal/...} cannot slip past a string comparison done here.
 *
 * <p>The comparison takes the same time whatever the header holds, so the token cannot be guessed
 * one character at a time from how fast the refusals come back.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    /** Header carrying the shared secret; the worker's {@code TOKEN_HEADER}. */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final InternalApiProperties properties;
    private final ObjectMapper json;

    public InternalTokenFilter(InternalApiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAuthorized(request.getHeader(TOKEN_HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), new ApiErrorResponse(
                "invalid_internal_token", "Missing or invalid " + TOKEN_HEADER + " header."));
    }

    private boolean isAuthorized(String sent) {
        if (!properties.hasToken() || sent == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sent.getBytes(StandardCharsets.UTF_8), properties.token().getBytes(StandardCharsets.UTF_8));
    }
}
