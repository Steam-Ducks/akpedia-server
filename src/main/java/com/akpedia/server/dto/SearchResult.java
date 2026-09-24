package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One document returned by semantic search, represented by its best matching chunk. */
public record SearchResult(
        @JsonProperty("document_id") Long documentId,
        String name,
        String description,
        double score,
        @JsonProperty("matched_chunk") String matchedChunk) {
}
