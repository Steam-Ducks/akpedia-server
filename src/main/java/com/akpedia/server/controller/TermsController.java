package com.akpedia.server.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.AcceptTermsRequest;
import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.DeclineTermsRequest;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsAcceptanceEntry;
import com.akpedia.server.dto.TermsAcceptanceResponse;
import com.akpedia.server.dto.TermsHistoryDetail;
import com.akpedia.server.dto.TermsHistoryEntry;
import com.akpedia.server.dto.TermsStatusResponse;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.TermsHistoryService;
import com.akpedia.server.service.TermsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The terms of use: the screen every user goes through after login, and the admin's page to
 * publish a new version.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Terms", description = "Versioned terms of use, published by admins and accepted by every user.")
public class TermsController {

    private static final String ERROR_SCHEMA = "application/json";

    private final TermsService terms;
    private final TermsHistoryService termsHistory;
    private final CurrentUser currentUser;

    public TermsController(TermsService terms, TermsHistoryService termsHistory, CurrentUser currentUser) {
        this.terms = terms;
        this.termsHistory = termsHistory;
        this.currentUser = currentUser;
    }

    @Operation(
            summary = "The current terms of use and the last version this user accepted",
            description = """
                    `current` is `null` while no version was published. The user must accept when `current`
                    is set and `accepted` is not that version; until then every other `/api` route answers
                    403 `terms_pending`.""")
    @GetMapping("/terms")
    public TermsStatusResponse status() {
        return terms.status(currentUser.keycloakId());
    }

    @Operation(
            summary = "Accept the current terms of use",
            description = """
                    `version` is the one the user was shown, as text (`"1.0"`). Each acceptance is kept,
                    with when it happened.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Accepted."),
        @ApiResponse(responseCode = "400", description = "`version` is missing.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "`terms_changed`: that is not the current version.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/terms/accept")
    public TermsAcceptanceResponse accept(@RequestBody @Valid AcceptTermsRequest request) {
        return terms.accept(currentUser.provisioned().id(), request.version(), request.optionalPermissionsOrEmpty());
    }

    @Operation(
            summary = "Decline the terms of use, right before logging out",
            description = """
                    Only records, in the audit trail, that the user declined `version`; the screen logs them
                    out next. They are asked again on their next login. Open before accepting, like the two
                    routes above.""")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Recorded."),
        @ApiResponse(responseCode = "400", description = "`version` is missing.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/terms/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@RequestBody @Valid DeclineTermsRequest request) {
        terms.decline(request.version());
    }

    @Operation(
            summary = "Who accepted which version of the terms, and when (ADMIN)",
            description = """
                    Newest first. `version` keeps one version (any case); `user` looks for part of the user's name
                    or e-mail. `size` defaults to 20, at most 100. A deleted account shows its anonymized row.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The page."),
        @ApiResponse(responseCode = "400", description = "`page` or `size` is out of bounds.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/admin/terms/acceptances")
    public PageResponse<TermsAcceptanceEntry> acceptances(
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return terms.acceptances(version, user, page, size);
    }

    @Operation(
            summary = "What each user did with each version of the terms, and when (ADMIN)",
            description = """
                    Newest first. `action` is `accepted`, `updated` (an optional permission turned on or off
                    after accepting) or `declined`; `id` is unique only together with it. `version` keeps one
                    version (any case); `user` looks for part of the user's name or e-mail. `size` defaults to
                    20, at most 100. A deleted account shows its anonymized row.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The page."),
        @ApiResponse(responseCode = "400", description = "`page` or `size` is out of bounds.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/admin/terms/history")
    public PageResponse<TermsHistoryEntry> history(
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return termsHistory.search(version, user, page, size);
    }

    @Operation(
            summary = "One entry of the terms history, with the optional permissions of its version (ADMIN)",
            description = """
                    For each permission: `granted_then`, as the user had it right after this entry (`null` for a
                    refusal), and `granted_now`, their latest choice in that version (`null` if they never chose).
                    `changed` marks the permission an `updated` entry turned on or off.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The entry."),
        @ApiResponse(responseCode = "404", description = "`terms_history_entry_not_found`.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/admin/terms/history/{action}/{id}")
    public TermsHistoryDetail historyEntry(@PathVariable String action, @PathVariable Long id) {
        return termsHistory.detail(action, id);
    }

    @Operation(
            summary = "Every published version of the terms of use, newest first (ADMIN)",
            description = "By publication date: the first one is the current terms.")
    @GetMapping("/admin/terms")
    public List<TermsVersionResponse> history() {
        return terms.history();
    }

    @Operation(
            summary = "Publish a new version of the terms of use (ADMIN)",
            description = """
                    The server numbers the version in sequence, the next `X.0` (`3.0` after `2.0`); versions
                    published before the numbering under other names are skipped when counting, and with
                    none numbered it is `1.0`. `content` is plain text, paragraphs separated by blank lines;
                    it is never read as HTML. The new version is the current one: every user, the admin
                    included, must accept it on their next request. Published versions never change.""")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Published, with the version it got."),
        @ApiResponse(responseCode = "400",
                description = "`invalid_request`: `title` or `content` is blank or too long.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409",
                description = "`terms_version_exists`: the number was taken by a version added straight to the database.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/admin/terms")
    @ResponseStatus(HttpStatus.CREATED)
    public TermsVersionResponse publish(@RequestBody @Valid PublishTermsRequest request) {
        return terms.publish(currentUser.id(), request);
    }
}
