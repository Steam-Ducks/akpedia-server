package com.akpedia.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.web.servlet.ResultMatcher;

import com.akpedia.server.dto.AdminProfileUpdateRequest;
import com.akpedia.server.dto.AdminRole;
import com.akpedia.server.dto.AdminUser;
import com.akpedia.server.dto.CreateUserRequest;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.CannotChangeOwnAdminException;
import com.akpedia.server.exception.CannotDisableSelfException;
import com.akpedia.server.exception.InvalidProfileException;
import com.akpedia.server.exception.InvalidRoleException;
import com.akpedia.server.exception.InvalidUserException;
import com.akpedia.server.exception.KeycloakUnavailableException;
import com.akpedia.server.exception.NothingChangedException;
import com.akpedia.server.exception.UserExistsException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.AdminUserService;
import com.akpedia.server.service.ProfileService;

@WebMvcTest(controllers = AdminUserController.class)
// The endpoints are under test, not who may call them: SecurityConfigTest covers that.
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class AdminUserControllerTest {

    private static final String ADMIN_ID = "admin-0000";
    private static final String ANA_ID = "ana-1111";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AdminUserService adminUsers;

    @MockBean
    private ProfileService profiles;

    @MockBean
    private CurrentUser currentUser;

    @BeforeEach
    void logIn() {
        given(currentUser.keycloakId()).willReturn(ADMIN_ID);
    }

    private static AdminUser ana(boolean enabled, String... roles) {
        return new AdminUser(ANA_ID, "Ana Souza", "ana@akpedia.test", "Engenharia", "12345678901", enabled, List.of(roles), "1.0", "2.0",
                OffsetDateTime.parse("2026-11-01T10:00:00Z"));
    }

    private void putJson(String path, String body, ResultMatcher... expected)
            throws Exception {
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpectAll(expected);
    }

    @Test
    @DisplayName("GET /admin/users answers a page of users in snake_case")
    void listAnswersPage() throws Exception {
        given(adminUsers.list("ana", 0, 20)).willReturn(new PageResponse<>(List.of(ana(true, "STANDARD")), 0, 20, 1, 1));

        mvc.perform(get("/api/v1/admin/users").param("search", "ana").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ANA_ID))
                .andExpect(jsonPath("$.content[0].name").value("Ana Souza"))
                .andExpect(jsonPath("$.content[0].sector").value("Engenharia"))
                .andExpect(jsonPath("$.content[0].enabled").value(true))
                .andExpect(jsonPath("$.content[0].roles[0]").value("STANDARD"))
                .andExpect(jsonPath("$.content[0].terms_accepted_version").value("1.0"))
                .andExpect(jsonPath("$.content[0].current_terms_version").value("2.0"))
                .andExpect(jsonPath("$.content[0].created_at").exists())
                .andExpect(jsonPath("$.total_elements").value(1));
    }

    @Test
    @DisplayName("GET /admin/roles answers the catalog")
    void rolesAnswersCatalog() throws Exception {
        given(adminUsers.roles()).willReturn(List.of(new AdminRole("ADMIN", "Administrador", "d", true, List.of("p"))));

        mvc.perform(get("/api/v1/admin/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("ADMIN"))
                .andExpect(jsonPath("$[0].label").value("Administrador"))
                .andExpect(jsonPath("$[0].assignable").value(true))
                .andExpect(jsonPath("$[0].permissions[0]").value("p"));
    }

    @Test
    @DisplayName("PUT /admin/users/{id}/roles passes the roles and the admin's own id")
    void setRoles() throws Exception {
        given(adminUsers.setRoles(ANA_ID, List.of("APPROVER"), ADMIN_ID)).willReturn(ana(true, "STANDARD", "APPROVER"));

        putJson("/api/v1/admin/users/" + ANA_ID + "/roles", "{\"roles\": [\"APPROVER\"]}",
                status().isOk(), jsonPath("$.roles[1]").value("APPROVER"));
    }

    @Test
    @DisplayName("PUT /admin/users/{id}/roles answers each refusal with its code")
    void setRolesRefusals() throws Exception {
        given(adminUsers.setRoles(eq(ANA_ID), eq(List.of("ROOT")), anyString())).willThrow(new InvalidRoleException("ROOT"));
        given(adminUsers.setRoles(eq(ADMIN_ID), eq(List.of()), anyString())).willThrow(new CannotChangeOwnAdminException());
        given(adminUsers.setRoles(eq("nope"), any(), anyString())).willThrow(new UserNotFoundException("nope"));
        given(adminUsers.setRoles(eq("down"), any(), anyString()))
                .willThrow(new KeycloakUnavailableException("ler o usuario", null));

        putJson("/api/v1/admin/users/" + ANA_ID + "/roles", "{\"roles\": [\"ROOT\"]}",
                status().isBadRequest(), jsonPath("$.code").value("invalid_role"));
        putJson("/api/v1/admin/users/" + ADMIN_ID + "/roles", "{\"roles\": []}",
                status().isConflict(), jsonPath("$.code").value("cannot_change_own_admin"));
        putJson("/api/v1/admin/users/nope/roles", "{\"roles\": []}",
                status().isNotFound(), jsonPath("$.code").value("user_not_found"));
        putJson("/api/v1/admin/users/down/roles", "{\"roles\": []}",
                status().isBadGateway(), jsonPath("$.code").value("keycloak_unavailable"));
        putJson("/api/v1/admin/users/" + ANA_ID + "/roles", "{}",
                status().isBadRequest(), jsonPath("$.code").value("invalid_request"));
    }

    @Test
    @DisplayName("POST /admin/users creates the user (201), and answers invalid_user and user_exists")
    void create() throws Exception {
        String body = """
                {"first_name": "Ana", "last_name": "Souza", "email": "ana@akpedia.test", "password": "s3gr3d0",
                 "cpf": "12345678901", "sector": "Setor 1", "roles": ["APPROVER"]}""";
        given(adminUsers.create(new CreateUserRequest("Ana", "Souza", "ana@akpedia.test", "s3gr3d0", "12345678901",
                "Setor 1", List.of("APPROVER")))).willReturn(ana(true, "STANDARD", "APPROVER"));
        given(adminUsers.create(new CreateUserRequest(null, null, "dup@akpedia.test", null, null, null, null)))
                .willThrow(new UserExistsException("dup@akpedia.test"));
        given(adminUsers.create(new CreateUserRequest(null, null, null, null, null, null, null)))
                .willThrow(new InvalidUserException("Informe o nome."));

        mvc.perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ANA_ID))
                .andExpect(jsonPath("$.roles[1]").value("APPROVER"));
        mvc.perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"dup@akpedia.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user_exists"));
        mvc.perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_user"))
                .andExpect(jsonPath("$.message").value("Informe o nome."));
    }

    @Test
    @DisplayName("PUT /admin/users/{id}/status enables or disables, and refuses disabling oneself")
    void setStatus() throws Exception {
        given(adminUsers.setEnabled(ANA_ID, false, ADMIN_ID)).willReturn(ana(false, "STANDARD"));
        given(adminUsers.setEnabled(ADMIN_ID, false, ADMIN_ID)).willThrow(new CannotDisableSelfException());

        putJson("/api/v1/admin/users/" + ANA_ID + "/status", "{\"enabled\": false}",
                status().isOk(), jsonPath("$.enabled").value(false));
        putJson("/api/v1/admin/users/" + ADMIN_ID + "/status", "{\"enabled\": false}",
                status().isConflict(), jsonPath("$.code").value("cannot_disable_self"));
        putJson("/api/v1/admin/users/" + ANA_ID + "/status", "{}",
                status().isBadRequest(), jsonPath("$.code").value("invalid_request"));
    }

    @Test
    @DisplayName("PUT /admin/users/{id}/profile edits the profile and answers the user with the cpf")
    void updateProfile() throws Exception {
        given(adminUsers.get(ANA_ID)).willReturn(ana(true, "STANDARD"));
        String body = """
                {"first_name": "Ana", "last_name": "Souza", "email": "ana@akpedia.test", "cpf": "123.456.789-01",
                 "sector": "Setor 2"}""";

        putJson("/api/v1/admin/users/" + ANA_ID + "/profile", body,
                status().isOk(), jsonPath("$.id").value(ANA_ID), jsonPath("$.cpf").value("12345678901"));
        verify(profiles).updateAsAdmin(ANA_ID,
                new AdminProfileUpdateRequest("Ana", "Souza", "ana@akpedia.test", "123.456.789-01", "Setor 2"));
    }

    @Test
    @DisplayName("PUT /admin/users/{id}/profile answers each refusal with its code")
    void updateProfileRefusals() throws Exception {
        willThrow(new InvalidProfileException("Informe o CPF.")).given(profiles).updateAsAdmin(eq("a"), any());
        willThrow(new NothingChangedException("Nada foi alterado.")).given(profiles).updateAsAdmin(eq("b"), any());
        willThrow(new UserExistsException("x@akpedia.test")).given(profiles).updateAsAdmin(eq("c"), any());
        willThrow(new UserNotFoundException("d")).given(profiles).updateAsAdmin(eq("d"), any());

        putJson("/api/v1/admin/users/a/profile", "{}", status().isBadRequest(),
                jsonPath("$.code").value("invalid_profile"), jsonPath("$.message").value("Informe o CPF."));
        putJson("/api/v1/admin/users/b/profile", "{}", status().isBadRequest(),
                jsonPath("$.code").value("nothing_changed"));
        putJson("/api/v1/admin/users/c/profile", "{}", status().isConflict(), jsonPath("$.code").value("user_exists"));
        putJson("/api/v1/admin/users/d/profile", "{}", status().isNotFound(),
                jsonPath("$.code").value("user_not_found"));
    }
}
