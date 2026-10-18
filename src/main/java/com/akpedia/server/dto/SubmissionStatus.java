package com.akpedia.server.dto;

import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;

/**
 * What the author of a document sees about it, derived from its {@link DocumentStatus} and, once
 * approved, its {@link ProcessingStatus}.
 *
 * <p>Deliberately coarser than the stored state: the author does not need the approval
 * machinery, only the answer to two questions -- can my colleagues find this yet, and if not,
 * why. {@code DRAFT} and {@code ARCHIVED} map to nothing here, which is why the submission
 * listing leaves those documents out instead of inventing a state for them.
 *
 * <p>Approval alone does not make a document findable: indexing only starts once it is approved,
 * so until it completes, search cannot reach it. Calling it {@link #PUBLISHED} in the meantime
 * would have the author tell colleagues it is ready while they still cannot find it.
 */
public enum SubmissionStatus {

    /** Submitted and waiting on an approver, whatever the indexing is doing. */
    UNDER_REVIEW,

    /** Approved and being indexed; search does not reach it yet. */
    PROCESSING,

    /** Approved and indexed, so colleagues can find it. */
    PUBLISHED,

    /**
     * Approved, but indexing failed, so search will not reach it on its own. Kept apart from
     * {@link #PROCESSING}, which would otherwise promise something that is not going to happen.
     */
    INDEXING_FAILED,

    /** Sent back by an approver; the review comment says why. */
    RETURNED;

    /**
     * The submission status of a document, or {@code null} when it is not a submission.
     *
     * @param status           the stored document status
     * @param processingStatus the stored indexing state; only consulted for an approved document
     * @return the derived status, or {@code null} for {@code DRAFT} and {@code ARCHIVED}
     */
    public static SubmissionStatus of(DocumentStatus status, ProcessingStatus processingStatus) {
        return switch (status) {
            case PENDING_APPROVAL -> UNDER_REVIEW;
            case APPROVED -> switch (processingStatus) {
                case PENDING, PROCESSING -> PROCESSING;
                case COMPLETED -> PUBLISHED;
                case FAILED -> INDEXING_FAILED;
            };
            case REJECTED -> RETURNED;
            case DRAFT, ARCHIVED -> null;
        };
    }
}
