package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The search vector, plus the model that produced it.
 *
 * @param model     model that produced the vector
 * @param embedding normalized vector of the search text
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryEmbeddingResponse(EmbeddingModelInfo model, List<Float> embedding) {
}
