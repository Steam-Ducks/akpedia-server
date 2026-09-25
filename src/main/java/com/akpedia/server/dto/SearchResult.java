package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One document returned by semantic search, represented by its best matching chunk.
 *
 * <p>A result carries what an interface needs to list it and to open it: which document it is, how
 * to name it, what it is made of, why it matched, and where in the document the match was found.
 *
 * @param documentId   identifier the document is reached by on the other document routes
 * @param name         document title
 * @param description  optional description given at upload
 * @param mimeType     format of the stored file; always {@code application/pdf}, since the upload
 *                     converts everything, but read from the document rather than assumed
 * @param score        cosine similarity between the query and the matched chunk, from -1 to 1
 * @param matchedChunk excerpt of the chunk that matched, trimmed to the configured length
 * @param chunkIndex   position of that chunk in the document, counted from 0, which is what tells
 *                     the reader whether the match is at the start of the document or deep into it
 */
public record SearchResult(
        @JsonProperty("document_id") Long documentId,
        String name,
        String description,
        @JsonProperty("mime_type") String mimeType,
        double score,
        @JsonProperty("matched_chunk") String matchedChunk,
        @JsonProperty("chunk_index") Integer chunkIndex) {
}
