package com.akpedia.server.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.config.SearchProperties;
import com.akpedia.server.dto.DocumentSummary;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.PendingDocument;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.InvalidDocumentListRequestException;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.EmbeddingRepository;

/**
 * Lists documents outside of search: the ones most recently changed, and the review queue.
 *
 * <p>The recent list holds the same documents search can return: indexed and not archived. A
 * document still being indexed or out of circulation would be offered there and then refused when
 * opened.
 *
 * <p>Each recent document carries an excerpt of its first chunk, cut to the same length as a search
 * snippet, so a card can say what the document is about even when it was uploaded without a
 * description.
 */
@Service
public class DocumentListService {

    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 50;

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final DocumentRepository documents;
    private final EmbeddingRepository embeddings;
    private final SearchProperties searchProperties;

    public DocumentListService(
            DocumentRepository documents,
            EmbeddingRepository embeddings,
            SearchProperties searchProperties) {
        this.documents = documents;
        this.embeddings = embeddings;
        this.searchProperties = searchProperties;
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> recent(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidDocumentListRequestException(
                    "limit must be between 1 and %d.".formatted(MAX_LIMIT));
        }

        List<Document> recent = documents.findRecent(
                ProcessingStatus.COMPLETED, DocumentStatus.ARCHIVED, PageRequest.of(0, limit));
        Map<Long, String> excerpts = excerptsOf(recent);

        return recent.stream()
                .map(document -> DocumentSummary.from(document, excerpts.get(document.getId())))
                .toList();
    }

    /**
     * The documents awaiting approval, one page at a time, the oldest first.
     *
     * @param requestedPage page index counted from 0; the first page when null
     * @param requestedSize documents per page, {@value #DEFAULT_PAGE_SIZE} when null
     * @throws InvalidDocumentListRequestException if the page is negative or the size out of range
     */
    @Transactional(readOnly = true)
    public PageResponse<PendingDocument> pending(Integer requestedPage, Integer requestedSize) {
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (page < 0) {
            throw new InvalidDocumentListRequestException("page must be 0 or greater.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidDocumentListRequestException(
                    "size must be between 1 and %d.".formatted(MAX_PAGE_SIZE));
        }

        return PageResponse.from(
                documents.findOldestFirstByStatus(DocumentStatus.PENDING_APPROVAL, PageRequest.of(page, size)),
                PendingDocument::from);
    }

    /** The excerpt of each document, by id, fetched in one query for the whole page. */
    private Map<Long, String> excerptsOf(List<Document> recent) {
        if (recent.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = recent.stream().map(Document::getId).toList();

        return embeddings.findFirstChunks(ids).stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> Snippets.of((String) row[1], searchProperties.snippetLength())));
    }
}
