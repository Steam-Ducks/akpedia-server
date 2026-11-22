package com.akpedia.server.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.akpedia.server.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The security chain's own refusals, in the API's error format: 401 {@code unauthorized} for a
 * missing, expired or invalid token, and 403 {@code forbidden} for a role that does not reach the
 * route. Spring's bearer-token handlers still set the status and the {@code WWW-Authenticate}
 * header; this only adds the body.
 */
public class ApiSecurityErrors implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler deniedHandler = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper json;

    public ApiSecurityErrors(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException, ServletException {
        entryPoint.commence(request, response, e);
        write(response, new ApiErrorResponse("unauthorized", "Entre no Akpedia para continuar."));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException, ServletException {
        deniedHandler.handle(request, response, e);
        write(response, new ApiErrorResponse("forbidden", "Você não tem permissão para acessar este recurso."));
    }

    private void write(HttpServletResponse response, ApiErrorResponse body) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
