package com.akpedia.server.dto;

import com.akpedia.server.entity.enums.DocumentStatus;

/**
 * What the author of a document sees about it, derived from its {@link DocumentStatus}.
 *
 * <p>Deliberately coarser than the stored status: the author does not need the approval
 * machinery, only the answer to two questions -- can my colleagues see this yet, and if it came
 * back, why. {@code DRAFT} and {@code ARCHIVED} map to nothing here, which is why the submission
 * listing leaves those documents out instead of inventing a fourth state for them.
 */
public enum SubmissionStatus {

    /** Submitted and waiting on an approver, whatever the indexing is doing. */
    UNDER_REVIEW,

    /** Approved, so colleagues can find it. */
    PUBLISHED,

    /** Sent back by an approver; the review comment says why. */
    RETURNED;

    /**
     * The submission status of a document, or {@code null} when it is not a submission.
     *
     * @param status the stored document status
     * @return the derived status, or {@code null} for {@code DRAFT} and {@code ARCHIVED}
     */
    public static SubmissionStatus of(DocumentStatus status) {
        return switch (status) {
            case PENDING_APPROVAL -> UNDER_REVIEW;
            case APPROVED -> PUBLISHED;
            case REJECTED -> RETURNED;
            case DRAFT, ARCHIVED -> null;
        };
    }
}
