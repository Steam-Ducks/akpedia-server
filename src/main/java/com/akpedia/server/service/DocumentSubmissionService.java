package com.akpedia.server.service;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.SubmissionSummary;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.exception.InvalidDocumentListRequestException;
import com.akpedia.server.repository.DocumentRepository;

/**
 * Lists an author's own documents, so they can tell what is already available to colleagues and
 * what came back for a fix.
 *
 * <p>Unlike the recent listing and search, this one deliberately shows unapproved documents: they
 * are the author's own, and the whole point is to say what is still under review and why something
 * was returned. What it does not show is anyone else's.
 */
@Service
public class DocumentSubmissionService {

    /** Statuses that are a submission; see {@link com.akpedia.server.dto.SubmissionStatus}. */
    static final List<DocumentStatus> SUBMITTED_STATUSES =
            List.of(DocumentStatus.PENDING_APPROVAL, DocumentStatus.APPROVED, DocumentStatus.REJECTED);

    static final int DEFAULT_SIZE = 10;
    static final int MAX_SIZE = 50;

    /**
     * Stands in for {@code categoryIds} when there is no category filter, the same placeholder
     * {@link SearchService} uses: the query still needs a non-empty collection to bind.
     */
    private static final List<Long> NO_CATEGORY_FILTER = List.of(-1L);

    private final DocumentRepository documents;

    public DocumentSubmissionService(DocumentRepository documents) {
        this.documents = documents;
    }

    /**
     * One page of the author's submissions, most recently submitted first.
     *
     * @param creatorId          whose submissions to list: the logged-in user
     * @param requestedPage      zero-based page; {@code null} means the first
     * @param requestedSize      results per page; {@code null} means {@value #DEFAULT_SIZE}
     * @param categoryIds        one or more category ids to filter by; {@code null} or empty
     *                           means every category
     * @return the page, with the totals a screen needs to page through it
     * @throws InvalidDocumentListRequestException if page or size are outside their bounds
     */
    @Transactional(readOnly = true)
    public PageResponse<SubmissionSummary> submissions(
            Long creatorId, Integer requestedPage, Integer requestedSize, Collection<Long> categoryIds) {

        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? DEFAULT_SIZE : requestedSize;
        if (page < 0) {
            throw new InvalidDocumentListRequestException("page cannot be negative.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidDocumentListRequestException("size must be between 1 and %d.".formatted(MAX_SIZE));
        }


        boolean hasCategories = categoryIds != null && !categoryIds.isEmpty();
        Collection<Long> safeCategoryIds = hasCategories ? categoryIds : NO_CATEGORY_FILTER;

        Page<Document> found = documents.findSubmissions(
                creatorId, SUBMITTED_STATUSES, hasCategories, safeCategoryIds, PageRequest.of(page, size));

        return PageResponse.from(found, SubmissionSummary::from);
    }
}
