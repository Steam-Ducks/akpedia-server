package com.akpedia.server.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.akpedia.server.service.AuditService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Records every {@code /api} request in the audit trail once it was answered: who, from where,
 * what, and with which status, refusals (401, 403) included.
 *
 * <p>Runs inside the security chain, ahead of the token check, and records after the rest of the
 * chain returned: by then the token was read into the security context, which is only cleared
 * further out. Reading the audit itself is left out, or every look at it would add to it.
 *
 * <p>Not a bean, for the same reason as {@link TermsGateFilter}. A failure to record is logged
 * and never reaches the response.
 */
public class RequestAuditFilter extends OncePerRequestFilter {

    static final String AUDIT_PATH = "/api/v1/admin/audit";

    private static final Logger log = LoggerFactory.getLogger(RequestAuditFilter.class);

    private final AuditService audit;

    public RequestAuditFilter(AuditService audit) {
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) {
            return true;
        }
        return HttpMethod.GET.matches(request.getMethod())
                && (path.equals(AUDIT_PATH) || path.startsWith(AUDIT_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        int status = HttpStatus.INTERNAL_SERVER_ERROR.value();
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            // An exception still escaping here becomes a 500 on the error dispatch.
            record(request, status, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private void record(HttpServletRequest request, int status, long durationMs) {
        try {
            audit.recordRequest(request, status, durationMs);
        } catch (RuntimeException e) {
            log.warn("Could not record {} {} in the audit trail", request.getMethod(), request.getRequestURI(), e);
        }
    }
}
