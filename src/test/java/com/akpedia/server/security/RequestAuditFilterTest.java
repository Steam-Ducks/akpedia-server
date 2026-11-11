package com.akpedia.server.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.akpedia.server.service.AuditService;

import jakarta.servlet.ServletException;

/** Which requests {@link RequestAuditFilter} records, and that it never breaks one. */
class RequestAuditFilterTest {

    private final AuditService audit = mock(AuditService.class);
    private final RequestAuditFilter filter = new RequestAuditFilter(audit);

    private void perform(String method, String path) throws Exception {
        filter.doFilter(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    @DisplayName("an /api request is recorded with the status it was answered with")
    void recordsApiRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/documents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(201));

        verify(audit).recordRequest(eq(request), eq(201), anyLong());
    }

    @Test
    @DisplayName("routes outside /api and reading the audit are left out; writing under /admin is not")
    void skipsExcludedRoutes() throws Exception {
        perform("GET", "/health");
        perform("GET", "/actuator/health");
        perform("GET", "/internal/documents/1/file");
        perform("GET", "/swagger-ui/index.html");
        perform("GET", "/v3/api-docs");
        perform("GET", "/api/v1/admin/audit");
        perform("GET", "/api/v1/admin/audit/actions");
        verify(audit, never()).recordRequest(any(), anyInt(), anyLong());

        perform("PUT", "/api/v1/admin/users/1/status");
        perform("GET", "/api/v1/admin/auditoria");
        verify(audit, times(2)).recordRequest(any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("a request that fails with an exception is recorded as a 500, and the exception goes on")
    void failedRequestIsA500() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/categories");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        verify(audit).recordRequest(eq(request), eq(500), anyLong());
    }

    @Test
    @DisplayName("a failure to record is swallowed")
    void recordingFailureIsSwallowed() throws Exception {
        willThrow(new IllegalStateException("database down")).given(audit).recordRequest(any(), anyInt(), anyLong());

        perform("GET", "/api/v1/categories");
    }
}
