package com.akpedia.server.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.dto.AccountDeletionReplayResponse;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.KeycloakUnavailableException;
import com.akpedia.server.exception.LastAdminException;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.AccountDeletionReplay;
import com.akpedia.server.service.AccountDeletionService;

@WebMvcTest(controllers = AccountController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AccountDeletionService deletion;

    @MockBean
    private AccountDeletionReplay replay;

    @MockBean
    private CurrentUser currentUser;

    @BeforeEach
    void logIn() {
        given(currentUser.keycloakId()).willReturn("ana-1111");
    }

    @Test
    @DisplayName("DELETE /me deletes the caller's account and answers 204")
    void deleteMe() throws Exception {
        mvc.perform(delete("/api/v1/me")).andExpect(status().isNoContent());

        verify(deletion).deleteOwnAccount("ana-1111");
    }

    @Test
    @DisplayName("DELETE /me answers last_admin and keycloak_unavailable")
    void deleteMeRefusals() throws Exception {
        willThrow(new LastAdminException()).given(deletion).deleteOwnAccount("ana-1111");
        mvc.perform(delete("/api/v1/me"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last_admin"));

        willThrow(new KeycloakUnavailableException("excluir o usuario", null)).given(deletion).deleteOwnAccount("ana-1111");
        mvc.perform(delete("/api/v1/me"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("keycloak_unavailable"));
    }

    @Test
    @DisplayName("POST /admin/account-deletions/replay answers what was replayed")
    void replay() throws Exception {
        given(replay.replay()).willReturn(new AccountDeletionReplayResponse(3, 1));

        mvc.perform(post("/api/v1/admin/account-deletions/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").value(3))
                .andExpect(jsonPath("$.deleted_again").value(1));
    }
}
