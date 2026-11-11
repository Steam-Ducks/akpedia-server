package com.akpedia.server.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.AdminProfileUpdateRequest;
import com.akpedia.server.dto.AdminRole;
import com.akpedia.server.dto.AdminUser;
import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.CreateUserRequest;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.UpdateUserRolesRequest;
import com.akpedia.server.dto.UpdateUserStatusRequest;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.AdminUserService;
import com.akpedia.server.service.ProfileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** The admin's users screen: the realm's users, creating them, their roles, and enabling or disabling them. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Users", description = "The realm's users, read and changed in Keycloak (ADMIN).")
public class AdminUserController {

    private static final String ERROR_SCHEMA = "application/json";

    private final AdminUserService adminUsers;
    private final ProfileService profiles;
    private final CurrentUser currentUser;

    public AdminUserController(AdminUserService adminUsers, ProfileService profiles, CurrentUser currentUser) {
        this.adminUsers = adminUsers;
        this.profiles = profiles;
        this.currentUser = currentUser;
    }

    @Operation(
            summary = "One page of the realm's users (ADMIN)",
            description = """
                    In Keycloak's order, by username (the email). `search` is looked for in the email, first and
                    last name. Each user carries their realm roles among `STANDARD`, `APPROVER` and `ADMIN`, the
                    last version of the terms they accepted and the current one. `sector` keeps one sector;
                    `role` keeps the users with `APPROVER` or `ADMIN`, or, for `STANDARD`, those with neither.
                    `size` defaults to 20, at most 100.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The page."),
        @ApiResponse(responseCode = "400", description = "`page` or `size` is out of bounds; `invalid_role`: unknown `role`.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak did not answer as expected.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/users")
    public PageResponse<AdminUser> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return adminUsers.list(search, sector, role, page, size);
    }

    @Operation(
            summary = "Create a user (ADMIN)",
            description = """
                    Creates the Keycloak account with its password (not temporary) and roles, email already
                    verified. `cpf` is 11 digits, `sector` one of the realm's sectors, `roles` any of `APPROVER`
                    and `ADMIN`. Recorded as `USER_CREATED`; the password is never logged nor recorded.""")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created; the user as the list shows them."),
        @ApiResponse(responseCode = "400", description = "`invalid_user`: a field is missing or invalid, the message says which.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "`user_exists`: there is a user with that email already.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak did not answer as expected.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUser create(@RequestBody CreateUserRequest request) {
        return adminUsers.create(request);
    }

    @Operation(
            summary = "The roles and what each allows (ADMIN)",
            description = "`STANDARD` comes with every account; only `APPROVER` and `ADMIN` are `assignable`.")
    @GetMapping("/roles")
    public List<AdminRole> roles() {
        return adminUsers.roles();
    }

    @Operation(
            summary = "Set the user's assignable roles (ADMIN)",
            description = """
                    `roles` lists every assignable role the user should have: the missing ones are given, the
                    others taken away. `STANDARD` is not touched. The change reaches the user's token on its
                    next refresh (within about 5 minutes) or at their next login. Recorded in the audit trail
                    as `USER_ROLES_CHANGED` when something changed.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The user as they are now."),
        @ApiResponse(responseCode = "400", description = "`invalid_role`: a role other than `APPROVER` or `ADMIN`.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "`user_not_found`: no such user.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "`cannot_change_own_admin`: the admin would take ADMIN from themself.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak did not answer as expected.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/users/{id}/roles")
    public AdminUser setRoles(@PathVariable("id") String id, @RequestBody @Valid UpdateUserRolesRequest request) {
        return adminUsers.setRoles(id, request.roles(), currentUser.keycloakId());
    }

    @Operation(
            summary = "Enable or disable the user's account (ADMIN)",
            description = """
                    A disabled account cannot log in. Mirrored on the user's row in `users` (`is_active`) when
                    there is one, and recorded in the audit trail as `USER_ENABLED` or `USER_DISABLED`.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The user as they are now."),
        @ApiResponse(responseCode = "400", description = "`enabled` is missing.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "`user_not_found`: no such user.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "`cannot_disable_self`: the admin would disable themself.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak did not answer as expected.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/users/{id}/status")
    public AdminUser setStatus(@PathVariable("id") String id, @RequestBody @Valid UpdateUserStatusRequest request) {
        return adminUsers.setEnabled(id, request.enabled(), currentUser.keycloakId());
    }

    @Operation(
            summary = "Edit the user's profile (ADMIN)",
            description = """
                    Every field, e-mail and CPF included: the user cannot change those themself, only ask. A
                    new e-mail is also the new login, taken as verified. `cpf` is 11 digits (dots and dashes
                    ignored), `sector` one of the realm's sectors. The change reaches the user's token on its
                    next refresh. Recorded as `USER_UPDATED` with the names of the fields changed, never their
                    values. A pending request the edit makes moot is left for the admin to reject.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The user as the list shows them now."),
        @ApiResponse(responseCode = "400",
                description = "`invalid_profile`: a field is missing or invalid; `nothing_changed`: it is all as it was.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "`user_not_found`: no such user.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "`user_exists`: another user has that e-mail.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak did not answer as expected.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/users/{id}/profile")
    public AdminUser updateProfile(@PathVariable("id") String id, @RequestBody AdminProfileUpdateRequest request) {
        profiles.updateAsAdmin(id, request);
        return adminUsers.get(id);
    }
}
