package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Everything the server needs to index a document: its chunks, with one vector each.
 *
 * @param filename   name of the uploaded file
 * @param model      model that produced the vectors
 * @param chunkCount number of chunks returned
 * @param chunks     the chunks, in document order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentEmbeddingResponse(
        String filename,
        EmbeddingModelInfo model,
        @JsonProperty("chunk_count") int chunkCount,
        List<DocumentChunk> chunks) {
}
