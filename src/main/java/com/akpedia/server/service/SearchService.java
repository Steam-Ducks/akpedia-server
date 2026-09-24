package com.akpedia.server.service;

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
                .map(SearchService::toResult)
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

    private static SearchResult toResult(Object[] row) {
        return new SearchResult(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (String) row[2],
                ((Number) row[4]).doubleValue(),
                (String) row[3]);
    }
}
