package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Which model produced the vectors, and in what shape.
 *
 * <p>Both embedding routes answer this same block, which is what lets the server check
 * that the index it is about to search was built by the model that embedded the query
 * before trusting any distance.
 *
 * @param name       model identifier, e.g. {@code intfloat/multilingual-e5-small}
 * @param dimensions size of every vector
 * @param normalized whether the vectors have length 1, so cosine equals dot product
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingModelInfo(String name, int dimensions, boolean normalized) {
}
