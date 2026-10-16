package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.akpedia.server.entity.Document;

/**
 * One of the author's own documents, as the "my submissions" screen shows it.
 *
 * <p>{@code reviewComment} and {@code reviewedAt} are only ever filled for a
 * {@link SubmissionStatus#RETURNED} document, since that is the one case where the author has to
 * be told something beyond the status. They are left out of the JSON when absent rather than sent
 * as nulls, so the payload of a published document does not carry fields about a rejection that
 * never happened.
 *
 * @param documentId       identifier the document is reached by on the other document routes
 * @param name             document title
 * @param description      optional description given at upload
 * @param category         name of the category the document is filed under
 * @param submissionStatus what the author sees: under review, published or returned
 * @param reviewComment    why it was returned; only present when returned
 * @param reviewedAt       when it was reviewed; only present when returned
 * @param createdAt        when it was submitted, which is what the listing is ordered by
 * @param updatedAt        when it last changed, or {@code null} if it never has
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmissionSummary(
        @JsonProperty("document_id") Long documentId,
        String name,
        String description,
        String category,
        @JsonProperty("submission_status") SubmissionStatus submissionStatus,
        @JsonProperty("review_comment") String reviewComment,
        @JsonProperty("reviewed_at") OffsetDateTime reviewedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {

    /**
     * Maps a document onto what its author is shown.
     *
     * <p>The review fields are dropped for anything but a returned document: a comment left on a
     * document that was then approved is part of its history, not of what the author has to act
     * on, and showing it beside {@code PUBLISHED} would read as if it were still returned.
     */
    public static SubmissionSummary from(Document document) {
        SubmissionStatus status = SubmissionStatus.of(document.getStatus());
        boolean returned = status == SubmissionStatus.RETURNED;

        return new SubmissionSummary(
                document.getId(),
                document.getName(),
                document.getDescription(),
                document.getCategory().getName(),
                status,
                returned ? document.getReviewComment() : null,
                returned ? document.getReviewedAt() : null,
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
