package com.akpedia.server.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.ApproveDocumentRequest;
import com.akpedia.server.dto.DocumentReviewResponse;
import com.akpedia.server.dto.RejectDocumentRequest;
import com.akpedia.server.service.DocumentReviewService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The approver's decision on a document from the review queue.
 *
 * <p>Kept apart from {@link DocumentController}, which serves documents to everyone: these routes
 * change a document's state, and are the ones that will be closed to approvers once there is a
 * login.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Review", description = "Approve or return a document waiting in the review queue.")
public class DocumentReviewController {

    private static final String ERROR_SCHEMA = "application/json";

    private final DocumentReviewService reviewService;

    public DocumentReviewController(DocumentReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(
            summary = "Approve a document, optionally correcting it, and send it to be indexed",
            description = """
                    Only for a document in `PENDING_APPROVAL`. The body is optional, and so is each of its
                    fields: `name`, `description` and `categoryId` replace what the author sent when given,
                    and keep it when left out.

                    The document becomes `APPROVED` with `processing_status: PROCESSING`, records the approver
                    and when, and -- once the change is committed -- its id is added to the Redis Stream
                    `documents:processing` for the indexing worker. Should that fail -- Redis down, or the
                    server stopping right after the commit -- the approval stands and the document is sent
                    again by a retry that runs in the background.

                    The approver is the configured default user while there is no login (see the README).""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Approved, and queued for indexing."),
        @ApiResponse(responseCode = "400", description = "The body is malformed, or `name` is blank or too long.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "No such document, or no such `categoryId`.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "The document is not waiting on a decision.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/documents/{id}/approve")
    public DocumentReviewResponse approve(
            @PathVariable("id") Long id,
            @RequestBody(required = false) @Valid ApproveDocumentRequest corrections) {
        return reviewService.approve(id, corrections != null ? corrections : ApproveDocumentRequest.NONE);
    }

    @Operation(
            summary = "Return a document to its author with the reason",
            description = """
                    Only for a document in `PENDING_APPROVAL`. `comment` is required: it is what the author
                    reads beside the returned document in their submissions.

                    The document becomes `REJECTED` with the comment and when it was decided. Its stored PDF
                    is deleted; the document itself stays, so the author still sees it and why it came back.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returned to the author."),
        @ApiResponse(responseCode = "400", description = "`comment` is missing or blank, or the body is malformed.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "No such document.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "The document is not waiting on a decision.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/documents/{id}/reject")
    public DocumentReviewResponse reject(
            @PathVariable("id") Long id,
            @RequestBody @Valid RejectDocumentRequest request) {
        return reviewService.reject(id, request.comment());
    }

    @Operation(
            summary = "Try indexing a failed document again",
            description = """
                    Only for a document whose `processing_status` is `FAILED`. It goes back to `PROCESSING`,
                    `processing_error` is cleared, and -- once the change is committed -- its id is added to
                    the Redis Stream `documents:processing` again, the same way a fresh approval is.

                    For a message stuck pending because its worker died, the recovery job already retries it
                    on its own, up to a limit; this route is for trying by hand after that limit, or whenever
                    someone wants to force it sooner.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Back to PROCESSING, and queued for indexing again."),
        @ApiResponse(responseCode = "404", description = "No such document.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "The document's indexing did not fail.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/documents/{id}/reprocess")
    public DocumentReviewResponse reprocess(@PathVariable("id") Long id) {
        return reviewService.reprocess(id);
    }
}
