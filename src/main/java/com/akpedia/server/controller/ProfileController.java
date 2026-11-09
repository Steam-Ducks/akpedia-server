package com.akpedia.server.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.ChangeRequest;
import com.akpedia.server.dto.NewChangeRequest;
import com.akpedia.server.dto.Profile;
import com.akpedia.server.dto.UpdateProfileRequest;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.ProfileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The user's own profile: reading it, changing their name and sector, and asking for a new e-mail or CPF. */
@RestController
@RequestMapping("/api/v1/me/profile")
@Tag(name = "Profile", description = "The user's own profile, and their requests to change their e-mail or CPF.")
public class ProfileController {

    private static final String ERROR_SCHEMA = "application/json";

    private final ProfileService profiles;
    private final CurrentUser currentUser;

    public ProfileController(ProfileService profiles, CurrentUser currentUser) {
        this.profiles = profiles;
        this.currentUser = currentUser;
    }

    @Operation(
            summary = "My profile",
            description = """
                    As Keycloak has it. `editable` lists the fields the user changes themself, `requestable` the
                    ones they can only ask an admin to change.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The profile."),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak did not answer as expected.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public Profile profile() {
        return profiles.profile(currentUser.keycloakId());
    }

    @Operation(
            summary = "Change my name and sector",
            description = """
                    `first_name` and `last_name` up to 255 characters, `sector` one of the realm's sectors. The
                    token carries the change from its next refresh. Recorded as `PROFILE_UPDATED` with the names
                    of the fields changed.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The profile as it is now."),
        @ApiResponse(responseCode = "400",
                description = "`invalid_profile`: a field is missing or invalid; `nothing_changed`: it is all as it was.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak did not answer as expected.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping
    public Profile update(@RequestBody UpdateProfileRequest request) {
        return profiles.update(currentUser.keycloakId(), currentUser.id(), request);
    }

    @Operation(summary = "My e-mail and CPF change requests", description = "Newest first, whatever their status.")
    @GetMapping("/requests")
    public List<ChangeRequest> requests() {
        return profiles.requests(currentUser.provisioned().id());
    }

    @Operation(
            summary = "Ask an admin to change my e-mail or CPF",
            description = """
                    `field` is `email` or `cpf`; `new_value` the value wanted (a CPF's dots and dashes are
                    ignored); `reason` optional, up to 500 characters. An admin approves or rejects it. Recorded
                    as `PROFILE_CHANGE_REQUESTED` with the field, never the value.""")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Asked; the request, pending."),
        @ApiResponse(responseCode = "400",
                description = "`invalid_change_request`: bad field or value, the current value, or a reason too long.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409",
                description = "`change_request_pending`: one for that field is waiting; `user_exists`: the e-mail is taken.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak did not answer as expected.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeRequest request(@RequestBody NewChangeRequest request) {
        return profiles.request(currentUser.keycloakId(), currentUser.provisioned().id(), request);
    }

    @Operation(
            summary = "Cancel my pending request",
            description = "The request is deleted. Recorded as `PROFILE_CHANGE_CANCELLED`.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Cancelled."),
        @ApiResponse(responseCode = "404", description = "`change_request_not_found`: no such request of the user's.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "`change_request_not_pending`: an admin decided it already.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable("id") Long id) {
        profiles.cancel(currentUser.provisioned().id(), id);
    }
}
