package com.akpedia.server.service;

import java.util.List;
import java.util.regex.Pattern;
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

    /** What marks a snippet as cut short. One character, so it barely eats into the budget. */
    private static final String ELLIPSIS = "…";

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

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
                snippetOf((String) row[MATCHED_CHUNK]),
                ((Number) row[CHUNK_INDEX]).intValue());
    }

    /**
     * Cuts the matched chunk down to a snippet worth putting in a list of results.
     *
     * <p>A chunk is however long akpedia-ml decided to make it, and it comes out of a PDF, so it
     * arrives with the line breaks of the page it was taken from. Runs of whitespace collapse into
     * single spaces to spend the budget on words instead of layout, and the cut lands on the last
     * whole word that fits, with an ellipsis marking that the text goes on.
     *
     * <p>The returned snippet never exceeds {@code akpedia.search.snippet-length}, ellipsis
     * included, so a caller can size a result card from the configured value alone. A single word
     * longer than half the budget is cut mid-word rather than turning the snippet into just an
     * ellipsis.
     */
    private String snippetOf(String chunk) {
        if (chunk == null) {
            return null;
        }
        String text = WHITESPACE_RUN.matcher(chunk).replaceAll(" ").strip();
        int limit = properties.snippetLength();
        if (text.length() <= limit) {
            return text;
        }

        int room = limit - ELLIPSIS.length();
        int cut = text.lastIndexOf(' ', room);
        if (cut < room / 2) {
            cut = room;
        }
        return text.substring(0, cut).stripTrailing() + ELLIPSIS;
    }
}
