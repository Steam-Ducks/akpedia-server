package com.akpedia.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.ChangeRequest;
import com.akpedia.server.dto.ChangeRequestStatusFilter;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.RejectChangeRequest;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.ProfileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The admin's queue of e-mail and CPF change requests: listing, approving and rejecting them. */
@RestController
@RequestMapping("/api/v1/admin/profile-requests")
@Tag(name = "Profile requests", description = "The users' requests to change their e-mail or CPF (ADMIN).")
public class AdminProfileRequestController {

    private static final String ERROR_SCHEMA = "application/json";

    private final ProfileService profiles;
    private final CurrentUser currentUser;

    public AdminProfileRequestController(ProfileService profiles, CurrentUser currentUser) {
        this.profiles = profiles;
        this.currentUser = currentUser;
    }

    @Operation(
            summary = "One page of the change requests (ADMIN)",
            description = """
                    Newest first, with who asked. `status` is `PENDING` (the default), `APPROVED`, `REJECTED` or
                    `ALL`; `size` defaults to 20, at most 100.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The page."),
        @ApiResponse(responseCode = "400", description = "`status`, `page` or `size` is invalid.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public PageResponse<ChangeRequest> list(
            @RequestParam(required = false) ChangeRequestStatusFilter status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return profiles.list(status, page, size);
    }

    @Operation(
            summary = "Approve a change request (ADMIN)",
            description = """
                    Applies the new value in Keycloak: a new e-mail is also the new login, taken as verified.
                    Recorded as `PROFILE_CHANGE_APPROVED`.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The request, approved."),
        @ApiResponse(responseCode = "404", description = "`change_request_not_found`: no such request.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409",
                description = "`change_request_not_pending`: decided already; `user_exists`: the e-mail was taken meanwhile.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "`keycloak_unavailable`: Keycloak did not answer as expected.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/{id}/approve")
    public ChangeRequest approve(@PathVariable("id") Long id) {
        return profiles.approve(id, currentUser.id());
    }

    @Operation(
            summary = "Reject a change request (ADMIN)",
            description = """
                    Nothing changes but the request. `note`, optional and up to 500 characters, is shown to the
                    user. The body may be left out. Recorded as `PROFILE_CHANGE_REJECTED`.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The request, rejected."),
        @ApiResponse(responseCode = "400", description = "`invalid_change_request`: the note is too long.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "`change_request_not_found`: no such request.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "`change_request_not_pending`: decided already.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/{id}/reject")
    public ChangeRequest reject(@PathVariable("id") Long id,
            @RequestBody(required = false) RejectChangeRequest request) {
        return profiles.reject(id, currentUser.id(), request == null ? null : request.note());
    }
}
