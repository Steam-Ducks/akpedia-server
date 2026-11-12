package com.akpedia.server.service;

import java.util.List;
import java.util.stream.Collectors;

import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.exception.EmbeddingException;

/**
 * What every similarity query does with the vector akpedia-ml answered for a text: checks it can
 * be compared with the stored chunks, and writes it the way Postgres reads a {@code vector}.
 *
 * <p>Shared by the search and the chat, so both refuse the same vectors.
 */
final class QueryVectors {

    private QueryVectors() {
    }

    /**
     * Refuses a vector that cannot be compared with the indexed ones.
     *
     * @throws EmbeddingException if it is incomplete, of another dimension, or names no model
     */
    static void validate(QueryEmbeddingResponse response) {
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

    /** The vector as pgvector's text form, {@code [0.1,0.2,...]}, for {@code CAST(... AS vector)}. */
    static String literal(List<Float> values) {
        return values.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
