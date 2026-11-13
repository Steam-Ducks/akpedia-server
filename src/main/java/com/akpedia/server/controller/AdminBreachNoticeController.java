package com.akpedia.server.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.BreachNoticeDetail;
import com.akpedia.server.dto.BreachNoticePreview;
import com.akpedia.server.dto.BreachNoticeRequest;
import com.akpedia.server.dto.BreachNoticeSendResult;
import com.akpedia.server.dto.BreachNoticeSummary;
import com.akpedia.server.dto.BreachNoticeTemplate;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.BreachNoticeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The admin's data breach notice screen: the suggested text, sending it, and what was sent. */
@RestController
@RequestMapping("/api/v1/admin/breach-notices")
@Tag(name = "Breach notices", description = "E-mailing the users affected by a data breach, once ANPD is informed (ADMIN).")
public class AdminBreachNoticeController {

    private static final String ERROR_SCHEMA = "application/json";

    private final BreachNoticeService breachNotices;
    private final CurrentUser currentUser;

    public AdminBreachNoticeController(BreachNoticeService breachNotices, CurrentUser currentUser) {
        this.breachNotices = breachNotices;
        this.currentUser = currentUser;
    }

    @Operation(
            summary = "The notice template, with its fields (ADMIN)",
            description = """
                    The subject and the plain text of the data breach notice document, with `{{campo}}` where each
                    field goes, and the list of fields: name, whether it is required, what goes in it and an
                    example. `nome_titular` is `automatic`: the server writes each recipient's name in it. An
                    optional field left blank takes its line out of the e-mail.""")
    @GetMapping("/template")
    public BreachNoticeTemplate template() {
        return breachNotices.template();
    }

    @Operation(
            summary = "Preview the notice e-mail, without sending it (ADMIN)",
            description = """
                    The same `subject`, `body` and `fields` as sending, checked the same way, answered as a
                    recipient will read them -- with the caller's own name in `{{nome_titular}}`. Nothing is
                    sent nor saved, and `anpd_informed` is not required, so the text can be drafted while ANPD
                    is being informed. Recipients are not looked at.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The e-mail, filled."),
        @ApiResponse(responseCode = "400",
                description = "`invalid_breach_notice`: subject or body missing or invalid, a required field blank or "
                        + "removed from the text, or a field the template does not have.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/preview")
    public BreachNoticePreview preview(@RequestBody BreachNoticeRequest request) {
        return breachNotices.preview(request, currentUser.id());
    }

    @Operation(
            summary = "Send the notice (ADMIN)",
            description = """
                    E-mails `subject` and `body` (plain text) to every user (`all_users: true`) or to the ones in
                    `user_ids`, never both. `fields` has the value of each `{{campo}}`, by name; each recipient gets
                    an e-mail of their own, with their name in `{{nome_titular}}`. A required field left blank, a
                    field the template does not have, `nome_titular` in `fields`, or a text that dropped a required
                    field (each stands for an item the law asks the notice to carry) is refused (400). Try it with
                    `POST .../preview` first.

                    A recipient is a user linked to a Keycloak account and not deleted; disabled accounts included.
                    `anpd_informed: true` confirms ANPD was informed: without it, nothing is sent and the answer is
                    422, whatever else the request has.

                    One e-mail failing does not stop the others. The answer says how many went out and which did
                    not, and why; each recipient's status stays on the notice (`GET .../{id}`). Recorded in the
                    audit trail as `BREACH_NOTICE_SENT`, with the counts.""")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Sent; how it went, failures included."),
        @ApiResponse(responseCode = "400",
                description = "`invalid_breach_notice`: subject or body missing or invalid, a required field blank or an "
                        + "unknown one, both or neither of `all_users` and `user_ids`, or a user that cannot be notified.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "`anpd_not_informed`: `anpd_informed` is not `true`.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BreachNoticeSendResult send(@RequestBody BreachNoticeRequest request) {
        return breachNotices.send(request, currentUser.id());
    }

    @Operation(
            summary = "One page of the notices sent, newest first (ADMIN)",
            description = "With how many e-mails went out and how many failed. `size` defaults to 20, at most 100.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The page."),
        @ApiResponse(responseCode = "400", description = "`page` or `size` is out of bounds.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public PageResponse<BreachNoticeSummary> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return breachNotices.list(page, size);
    }

    @Operation(summary = "A notice as it was sent, and how it went for each recipient (ADMIN)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The notice."),
        @ApiResponse(responseCode = "404", description = "`breach_notice_not_found`: no such notice.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public BreachNoticeDetail get(@PathVariable("id") Long id) {
        return breachNotices.get(id);
    }
}
