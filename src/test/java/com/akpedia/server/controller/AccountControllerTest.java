package com.akpedia.server.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import java.util.List;

import com.akpedia.server.dto.AccountDeletionReplayResponse;
import com.akpedia.server.dto.MeResponse;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.KeycloakUnavailableException;
import com.akpedia.server.exception.LastAdminException;
import com.akpedia.server.security.CpfProblem;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.security.ProvisionedUser;
import com.akpedia.server.service.AccountDeletionReplay;
import com.akpedia.server.service.AccountDeletionService;
import com.akpedia.server.service.MeService;

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

    @MockBean
    private MeService me;

    @BeforeEach
    void logIn() {
        given(currentUser.keycloakId()).willReturn("ana-1111");
    }

    @Test
    @DisplayName("GET /me answers the user, their sector and roles, and terms_pending, in snake_case")
    void getMe() throws Exception {
        given(currentUser.provisioned()).willReturn(new ProvisionedUser(7L, null));
        given(me.me(eq(7L), eq("ana-1111"), any(), isNull())).willReturn(
                new MeResponse(7L, "Ana Souza", "ana@akpedia.test", "Engenharia", List.of("STANDARD"), true, null));

        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.sector").value("Engenharia"))
                .andExpect(jsonPath("$.roles[0]").value("STANDARD"))
                .andExpect(jsonPath("$.terms_pending").value(true))
                .andExpect(jsonPath("$.cpf_problem").isEmpty());
    }

    @Test
    @DisplayName("GET /me answers a refused CPF in cpf_problem instead of failing")
    void getMeWithRefusedCpf() throws Exception {
        given(currentUser.provisioned()).willReturn(new ProvisionedUser(7L, CpfProblem.INVALID));
        given(me.me(eq(7L), eq("ana-1111"), any(), eq("invalid_cpf"))).willReturn(new MeResponse(
                7L, "Ana Souza", "ana@akpedia.test", "Engenharia", List.of("STANDARD"), false, "invalid_cpf"));

        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf_problem").value("invalid_cpf"));
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
