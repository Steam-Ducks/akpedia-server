package com.akpedia.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.akpedia.server.dto.NewChangeRequest;
import com.akpedia.server.dto.Profile;
import com.akpedia.server.dto.UpdateProfileRequest;
import com.akpedia.server.entity.enums.ChangeRequestStatus;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.ChangeRequestNotFoundException;
import com.akpedia.server.exception.ChangeRequestNotPendingException;
import com.akpedia.server.exception.ChangeRequestPendingException;
import com.akpedia.server.exception.InvalidChangeRequestException;
import com.akpedia.server.exception.InvalidProfileException;
import com.akpedia.server.exception.NothingChangedException;
import com.akpedia.server.exception.UserExistsException;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.ProfileService;

@WebMvcTest(controllers = ProfileController.class)
// The endpoints are under test, not who may call them: SecurityConfigTest covers that.
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class ProfileControllerTest {

    private static final String ANA_ID = "ana-1111";
    private static final long ANA_ROW = 42L;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProfileService profiles;

    @MockBean
    private CurrentUser currentUser;

    @BeforeEach
    void logIn() {
        given(currentUser.keycloakId()).willReturn(ANA_ID);
        given(currentUser.id()).willReturn(ANA_ROW);
    }

    private static Profile profile(String firstName) {
        return new Profile(firstName, "Souza", "ana@akpedia.test", "12345678901", "Setor 1", List.of("STANDARD"),
                Profile.EDITABLE, Profile.REQUESTABLE);
    }

    static ChangeRequest pendingEmail() {
        return new ChangeRequest(7L, "email", "nova@akpedia.test", "Casei", ChangeRequestStatus.PENDING,
                OffsetDateTime.parse("2026-11-08T10:00:00Z"), null, null, null,
                new ChangeRequest.Requester(ANA_ID, "Ana Souza", "ana@akpedia.test"));
    }

    @Test
    @DisplayName("GET /me/profile answers the profile in snake_case, with what is editable and requestable")
    void readsProfile() throws Exception {
        given(profiles.profile(ANA_ID)).willReturn(profile("Ana"));

        mvc.perform(get("/api/v1/me/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first_name").value("Ana"))
                .andExpect(jsonPath("$.last_name").value("Souza"))
                .andExpect(jsonPath("$.email").value("ana@akpedia.test"))
                .andExpect(jsonPath("$.cpf").value("12345678901"))
                .andExpect(jsonPath("$.sector").value("Setor 1"))
                .andExpect(jsonPath("$.roles[0]").value("STANDARD"))
                .andExpect(jsonPath("$.editable").value(org.hamcrest.Matchers.contains("first_name", "last_name", "sector")))
                .andExpect(jsonPath("$.requestable").value(org.hamcrest.Matchers.contains("email", "cpf")));
    }

    @Test
    @DisplayName("PUT /me/profile passes the fields, and answers invalid_profile and nothing_changed")
    void updatesProfile() throws Exception {
        given(profiles.update(ANA_ID, ANA_ROW, new UpdateProfileRequest("Ana Maria", "Souza", "Setor 2")))
                .willReturn(profile("Ana Maria"));
        given(profiles.update(ANA_ID, ANA_ROW, new UpdateProfileRequest("", "Souza", "Setor 2")))
                .willThrow(new InvalidProfileException("Informe o nome."));
        given(profiles.update(ANA_ID, ANA_ROW, new UpdateProfileRequest("Ana", "Souza", "Setor 1")))
                .willThrow(new NothingChangedException("Nada foi alterado."));

        mvc.perform(put("/api/v1/me/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"first_name\": \"Ana Maria\", \"last_name\": \"Souza\", \"sector\": \"Setor 2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.first_name").value("Ana Maria"));
        mvc.perform(put("/api/v1/me/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"first_name\": \"\", \"last_name\": \"Souza\", \"sector\": \"Setor 2\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_profile"));
        mvc.perform(put("/api/v1/me/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"first_name\": \"Ana\", \"last_name\": \"Souza\", \"sector\": \"Setor 1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("nothing_changed"));
    }

    @Test
    @DisplayName("GET /me/profile/requests answers the user's requests in snake_case")
    void listsRequests() throws Exception {
        given(profiles.requests(ANA_ROW)).willReturn(List.of(pendingEmail()));

        mvc.perform(get("/api/v1/me/profile/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].field").value("email"))
                .andExpect(jsonPath("$[0].new_value").value("nova@akpedia.test"))
                .andExpect(jsonPath("$[0].reason").value("Casei"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].created_at").exists())
                .andExpect(jsonPath("$[0].decided_at").isEmpty())
                .andExpect(jsonPath("$[0].decision_note").isEmpty())
                .andExpect(jsonPath("$[0].decided_by_name").isEmpty())
                .andExpect(jsonPath("$[0].user.id").value(ANA_ID));
    }

    @Test
    @DisplayName("POST /me/profile/requests creates (201), and answers each refusal with its code")
    void createsRequest() throws Exception {
        given(profiles.request(ANA_ID, ANA_ROW, new NewChangeRequest("email", "nova@akpedia.test", "Casei")))
                .willReturn(pendingEmail());
        given(profiles.request(eq(ANA_ID), eq(ANA_ROW), eq(new NewChangeRequest("email", "x", null))))
                .willThrow(new InvalidChangeRequestException("Informe um e-mail válido."));
        given(profiles.request(eq(ANA_ID), eq(ANA_ROW), eq(new NewChangeRequest("cpf", "98765432100", null))))
                .willThrow(new ChangeRequestPendingException("Já há uma solicitação."));
        given(profiles.request(eq(ANA_ID), eq(ANA_ROW), eq(new NewChangeRequest("email", "bia@akpedia.test", null))))
                .willThrow(new UserExistsException("bia@akpedia.test"));

        mvc.perform(post("/api/v1/me/profile/requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\": \"email\", \"new_value\": \"nova@akpedia.test\", \"reason\": \"Casei\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/v1/me/profile/requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\": \"email\", \"new_value\": \"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_change_request"));
        mvc.perform(post("/api/v1/me/profile/requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\": \"cpf\", \"new_value\": \"98765432100\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("change_request_pending"));
        mvc.perform(post("/api/v1/me/profile/requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\": \"email\", \"new_value\": \"bia@akpedia.test\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("user_exists"));
    }

    @Test
    @DisplayName("DELETE /me/profile/requests/{id} cancels (204), 404 change_request_not_found, 409 not pending")
    void cancelsRequest() throws Exception {
        willThrow(new ChangeRequestNotFoundException(8L)).given(profiles).cancel(ANA_ROW, 8L);
        willThrow(new ChangeRequestNotPendingException("Esta solicitação já foi analisada."))
                .given(profiles).cancel(ANA_ROW, 9L);

        mvc.perform(delete("/api/v1/me/profile/requests/7")).andExpect(status().isNoContent());
        verify(profiles).cancel(ANA_ROW, 7L);
        mvc.perform(delete("/api/v1/me/profile/requests/8"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("change_request_not_found"));
        mvc.perform(delete("/api/v1/me/profile/requests/9"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("change_request_not_pending"));
    }

    @Test
    @DisplayName("a body that is not JSON is invalid_request")
    void unreadableBody() throws Exception {
        mvc.perform(post("/api/v1/me/profile/requests").contentType(MediaType.APPLICATION_JSON).content("nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        verify(profiles, org.mockito.Mockito.never()).request(any(), anyLong(), any());
    }
}
