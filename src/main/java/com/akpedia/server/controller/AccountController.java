package com.akpedia.server.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.AccountDeletionReplayResponse;
import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.MeResponse;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.AccountDeletionReplay;
import com.akpedia.server.service.AccountDeletionService;
import com.akpedia.server.service.MeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The user's own account: who they are, and deleting it. And the admin's replay of the deletions
 * after a restore.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Account",
        description = "Who the user is, deleting one's own account, and replaying deletions after a backup restore.")
public class AccountController {

    private static final String ERROR_SCHEMA = "application/json";

    private final AccountDeletionService deletion;
    private final AccountDeletionReplay replay;
    private final CurrentUser currentUser;
    private final MeService me;

    public AccountController(AccountDeletionService deletion, AccountDeletionReplay replay, CurrentUser currentUser,
            MeService me) {
        this.deletion = deletion;
        this.replay = replay;
        this.currentUser = currentUser;
        this.me = me;
    }

    @Operation(
            summary = "Who I am",
            description = """
                    The signed-in user's row (created on their first request), sector and realm roles, and
                    `terms_pending`: whether they must accept the current terms before using the rest of the API.
                    Open before accepting the terms.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The user."),
        @ApiResponse(responseCode = "401", description = "`unauthorized`: no valid token.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/me")
    public MeResponse me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return me.me(currentUser.id(), currentUser.keycloakId(),
                authentication == null ? List.of() : authentication.getAuthorities());
    }

    @Operation(
            summary = "Delete my account",
            description = """
                    For good: the Keycloak account is deleted and the user's row in `users` anonymized (their
                    documents stay); their audit entries keep only a pseudonym. Written first to the deletion log,
                    kept outside the database, so a restored backup can be cleaned again. Open before
                    accepting the terms. The screen logs the user out next.""")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Deleted."),
        @ApiResponse(responseCode = "409", description = "`last_admin`: the user is the only enabled admin.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak failed; trying again finishes it.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe() {
        deletion.deleteOwnAccount(currentUser.keycloakId());
    }

    @Operation(
            summary = "Replay the account deletions (ADMIN)",
            description = """
                    Deletes again every account in the deletion log that a restored backup brought back, in
                    Keycloak and in `users`. Also runs on every start. `entries` is how many deletions the log
                    holds, `deleted_again` how many had come back.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Replayed."),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak failed.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/admin/account-deletions/replay")
    public AccountDeletionReplayResponse replay() {
        return replay.replay();
    }
}
