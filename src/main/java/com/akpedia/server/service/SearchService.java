package com.akpedia.server.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.config.SearchProperties;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.dto.SearchResult;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.exception.EmbeddingException;
import com.akpedia.server.exception.InvalidSearchRequestException;
import com.akpedia.server.repository.EmbeddingRepository;

/** Coordinates query embedding and document similarity search. */
@Service
public class SearchService {

    /** Column positions of the similarity query, in the order it selects them. */
    private static final int DOCUMENT_ID = 0;
    private static final int NAME = 1;
    private static final int DESCRIPTION = 2;
    private static final int MIME_TYPE = 3;
    private static final int MATCHED_CHUNK = 4;
    private static final int CHUNK_INDEX = 5;
    private static final int SCORE = 6;
    private static final int CATEGORY = 7;
    private static final int RESPONSIBLE_NAME = 8;
    private static final int UPDATED_AT = 9;

    private final EmbeddingClient embeddingClient;
    private final EmbeddingRepository embeddings;
    private final SearchProperties properties;

    public SearchService(
            EmbeddingClient embeddingClient,
            EmbeddingRepository embeddings,
            SearchProperties properties) {
        this.embeddingClient = embeddingClient;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    public List<SearchResult> search(String query, Integer requestedLimit) {
        validateQuery(query);
        int limit = normalizeLimit(requestedLimit);

        QueryEmbeddingResponse embedding = embeddingClient.embedQuery(query.trim());
        validateEmbedding(embedding);

        return embeddings.search(
                        vectorLiteral(embedding.embedding()),
                        embedding.model().name(),
                        embedding.model().dimensions(),
                        properties.minimumScore(),
                        limit)
                .stream()
                .map(this::toResult)
                .toList();
    }

    private int normalizeLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? properties.defaultLimit() : requestedLimit;
        if (limit < 1 || limit > properties.maxLimit()) {
            throw new InvalidSearchRequestException(
                    "limit must be between 1 and %d.".formatted(properties.maxLimit()));
        }
        return limit;
    }

    private static void validateQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new InvalidSearchRequestException("q must not be blank.");
        }
    }

    private static void validateEmbedding(QueryEmbeddingResponse response) {
        if (response == null || response.model() == null || response.embedding() == null) {
            throw new EmbeddingException("The embedding service returned an incomplete query vector.");
        }
        if (response.model().dimensions() != Embedding.VECTOR_DIMENSIONS
                || response.embedding().size() != Embedding.VECTOR_DIMENSIONS) {
            throw new EmbeddingException(
                    "The query vector dimensions do not match the indexed vectors.");
        }
        if (response.model().name() == null || response.model().name().isBlank()) {
            throw new EmbeddingException("The query vector has no model name.");
        }
    }

    private static String vectorLiteral(List<Float> values) {
        return values.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * Builds a result out of one row of the similarity query.
     *
     * <p>The columns are read by position, in the order the query selects them -- named here
     * instead of inlined, because a native query hands back an untyped array and a shifted index
     * would quietly put the score where the chunk belongs.
     */
    private SearchResult toResult(Object[] row) {
        return new SearchResult(
                ((Number) row[DOCUMENT_ID]).longValue(),
                (String) row[NAME],
                (String) row[DESCRIPTION],
                (String) row[MIME_TYPE],
                ((Number) row[SCORE]).doubleValue(),
                Snippets.of((String) row[MATCHED_CHUNK], properties.snippetLength()),
                ((Number) row[CHUNK_INDEX]).intValue(),
                (String) row[CATEGORY],
                (String) row[RESPONSIBLE_NAME],
                toOffsetDateTime(row[UPDATED_AT]));
    }

    /**
     * Reads a {@code timestamptz} column of a native query. Depending on the driver and Hibernate
     * version it arrives as a {@link Timestamp}, an {@link Instant} or already as an
     * {@link OffsetDateTime}; all of them name the same instant, answered here in UTC.
     */
    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        throw new IllegalStateException("Unexpected type for updated_at: " + value.getClass().getName());
    }
}
