package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * What the akpedia-ml worker sends back once it has indexed a document: the response of its
 * {@code POST /api/v1/documents/process}, plus the id of the stream message it was working on.
 *
 * @param filename   name of the file the worker read
 * @param model      model that produced the vectors
 * @param chunkCount number of chunks sent
 * @param chunks     the chunks, in document order, one vector each
 * @param messageId  id of the Redis Stream entry, acknowledged once the result is stored
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexedDocumentRequest(
        String filename,
        @NotNull EmbeddingModelInfo model,
        @JsonProperty("chunk_count") Integer chunkCount,
        @NotEmpty List<@NotNull DocumentChunk> chunks,
        @NotBlank @Pattern(regexp = StreamMessageIds.PATTERN) String messageId) {
}
