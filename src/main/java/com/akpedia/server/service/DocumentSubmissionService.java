package com.akpedia.server.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.config.DefaultUserProvider;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.SubmissionSummary;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.exception.InvalidDocumentListRequestException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.UserRepository;

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

    private final DocumentRepository documents;
    private final UserRepository users;
    private final DefaultUserProvider defaultUser;

    public DocumentSubmissionService(
            DocumentRepository documents,
            UserRepository users,
            DefaultUserProvider defaultUser) {
        this.documents = documents;
        this.users = users;
        this.defaultUser = defaultUser;
    }

    /**
     * One page of the author's submissions, most recently submitted first.
     *
     * @param requestedCreatorId whose submissions to list; {@code null} falls back to the
     *                           configured default user, the only author this sprint has
     * @param requestedPage      zero-based page; {@code null} means the first
     * @param requestedSize      results per page; {@code null} means {@value #DEFAULT_SIZE}
     * @return the page, with the totals a screen needs to page through it
     * @throws InvalidDocumentListRequestException if page or size are outside their bounds
     * @throws UserNotFoundException               if no user exists with the given id
     */
    @Transactional(readOnly = true)
    public PageResponse<SubmissionSummary> submissions(
            Long requestedCreatorId, Integer requestedPage, Integer requestedSize) {

        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? DEFAULT_SIZE : requestedSize;
        if (page < 0) {
            throw new InvalidDocumentListRequestException("page cannot be negative.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidDocumentListRequestException("size must be between 1 and %d.".formatted(MAX_SIZE));
        }

        Long creatorId = requestedCreatorId == null ? defaultUser.userId() : requestedCreatorId;
        // Checked so that a mistyped id answers 404 instead of an empty page, which would read as
        // "you have submitted nothing" -- a different thing, and the one the author would believe.
        if (!users.existsById(creatorId)) {
            throw new UserNotFoundException(creatorId);
        }

        Page<Document> found = documents.findSubmissions(
                creatorId, SUBMITTED_STATUSES, PageRequest.of(page, size));

        return PageResponse.from(found, SubmissionSummary::from);
    }
}
