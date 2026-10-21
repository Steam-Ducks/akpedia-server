package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;

/**
 * A document as it stands right after the approver's decision.
 *
 * <p>Every field is always present, {@code null} when the decision did not set it: an approval
 * carries no {@code review_comment}, a rejection no {@code approver_id} or {@code approved_at}.
 *
 * @param id               the document
 * @param name             title, as corrected by the approver if it was
 * @param description      description, as corrected by the approver if it was
 * @param categoryId       category it is filed under, as corrected by the approver if it was
 * @param status           {@code APPROVED} or {@code REJECTED}
 * @param processingStatus {@code PROCESSING} once approved, since indexing comes next
 * @param approverId       who approved it
 * @param approvedAt       when it was approved
 * @param reviewComment    why it was returned
 * @param reviewedAt       when the decision was made, either way
 */
public record DocumentReviewResponse(
        Long id,
        String name,
        String description,
        @JsonProperty("category_id") Long categoryId,
        DocumentStatus status,
        @JsonProperty("processing_status") ProcessingStatus processingStatus,
        @JsonProperty("approver_id") Long approverId,
        @JsonProperty("approved_at") OffsetDateTime approvedAt,
        @JsonProperty("review_comment") String reviewComment,
        @JsonProperty("reviewed_at") OffsetDateTime reviewedAt) {

    public static DocumentReviewResponse from(Document document) {
        return new DocumentReviewResponse(
                document.getId(),
                document.getName(),
                document.getDescription(),
                document.getCategory().getId(),
                document.getStatus(),
                document.getProcessingStatus(),
                document.getApprover() != null ? document.getApprover().getId() : null,
                document.getApprovedAt(),
                document.getReviewComment(),
                document.getReviewedAt());
    }
}
