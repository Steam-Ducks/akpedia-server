package com.akpedia.server.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.dto.ChangeRequest;
import com.akpedia.server.dto.ChangeRequestStatusFilter;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.entity.enums.ChangeRequestStatus;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.ChangeRequestNotFoundException;
import com.akpedia.server.exception.ChangeRequestNotPendingException;
import com.akpedia.server.exception.UserExistsException;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.ProfileService;

@WebMvcTest(controllers = AdminProfileRequestController.class)
// The endpoints are under test, not who may call them: SecurityConfigTest covers that.
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class AdminProfileRequestControllerTest {

    private static final long ADMIN_ROW = 1L;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProfileService profiles;

    @MockBean
    private CurrentUser currentUser;

    @BeforeEach
    void logIn() {
        given(currentUser.id()).willReturn(ADMIN_ROW);
    }

    private static ChangeRequest decided(ChangeRequestStatus status, String note) {
        return new ChangeRequest(7L, "cpf", "98765432100", null, status, OffsetDateTime.parse("2026-11-08T10:00:00Z"),
                OffsetDateTime.parse("2026-11-08T11:00:00Z"), note, "Admin Akpedia",
                new ChangeRequest.Requester("ana-1111", "Ana Souza", "ana@akpedia.test"));
    }

    @Test
    @DisplayName("GET /admin/profile-requests passes the status and answers a page with the requester")
    void lists() throws Exception {
        given(profiles.list(ChangeRequestStatusFilter.ALL, 0, 20)).willReturn(
                new PageResponse<>(List.of(decided(ChangeRequestStatus.APPROVED, null)), 0, 20, 1, 1));
        given(profiles.list(null, null, null)).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/admin/profile-requests").param("status", "ALL").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].user.name").value("Ana Souza"))
                .andExpect(jsonPath("$.content[0].user.email").value("ana@akpedia.test"))
                .andExpect(jsonPath("$.content[0].decided_by_name").value("Admin Akpedia"))
                .andExpect(jsonPath("$.total_elements").value(1));
        mvc.perform(get("/api/v1/admin/profile-requests")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/profile-requests").param("status", "OPEN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    @DisplayName("approving answers the request, and change_request_not_pending, not_found and user_exists")
    void approves() throws Exception {
        given(profiles.approve(7L, ADMIN_ROW)).willReturn(decided(ChangeRequestStatus.APPROVED, null));
        given(profiles.approve(8L, ADMIN_ROW)).willThrow(new ChangeRequestNotPendingException("x"));
        given(profiles.approve(9L, ADMIN_ROW)).willThrow(new ChangeRequestNotFoundException(9L));
        given(profiles.approve(10L, ADMIN_ROW)).willThrow(new UserExistsException("bia@akpedia.test"));

        mvc.perform(post("/api/v1/admin/profile-requests/7/approve"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/v1/admin/profile-requests/8/approve"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("change_request_not_pending"));
        mvc.perform(post("/api/v1/admin/profile-requests/9/approve"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("change_request_not_found"));
        mvc.perform(post("/api/v1/admin/profile-requests/10/approve"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("user_exists"));
    }

    @Test
    @DisplayName("rejecting passes the note, and takes no body at all too")
    void rejects() throws Exception {
        given(profiles.reject(7L, ADMIN_ROW, "Confira o CPF")).willReturn(decided(ChangeRequestStatus.REJECTED,
                "Confira o CPF"));
        given(profiles.reject(7L, ADMIN_ROW, null)).willReturn(decided(ChangeRequestStatus.REJECTED, null));

        mvc.perform(post("/api/v1/admin/profile-requests/7/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"Confira o CPF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decision_note").value("Confira o CPF"));
        mvc.perform(post("/api/v1/admin/profile-requests/7/reject"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision_note").isEmpty());
    }
}
