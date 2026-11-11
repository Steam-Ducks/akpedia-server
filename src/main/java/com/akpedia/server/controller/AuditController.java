package com.akpedia.server.controller;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.AuditEntry;
import com.akpedia.server.dto.AuditKind;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.service.AuditService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The admin's audit screen: what users did, and every request they made. */
@RestController
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "Audit", description = "The audit trail of named events and /api requests (ADMIN).")
public class AuditController {

    private static final String ERROR_SCHEMA = "application/json";

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @Operation(
            summary = "One page of the audit trail, newest first (ADMIN)",
            description = """
                    `kind` picks the named events (`EVENT`, the default), the requests (`REQUEST`, action
                    `HTTP_REQUEST`) or both (`ALL`). Every other filter is optional: `action` exact, `user` part
                    of the email in any case, `from` and `to` ISO-8601 date-times, both inclusive. `size` defaults
                    to 50, at most 200. Reading the audit is itself not recorded.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The page."),
        @ApiResponse(responseCode = "400", description = "A filter is not valid, or `page`/`size` is out of bounds.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public PageResponse<AuditEntry> search(
            @RequestParam(required = false) AuditKind kind,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @Parameter(description = "Part of the user's email.") String user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return audit.search(kind, action, user, from, to, page, size);
    }

    @Operation(summary = "The named event actions recorded so far, in alphabetical order (ADMIN)")
    @GetMapping("/actions")
    public List<String> actions() {
        return audit.eventActions();
    }
}
