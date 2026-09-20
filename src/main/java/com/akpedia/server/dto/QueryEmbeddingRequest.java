package com.akpedia.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * What the caller typed in the search box.
 *
 * @param text search text; rejected here when blank, so an obviously empty search never
 *             costs a round trip to the embedding service
 */
public record QueryEmbeddingRequest(
        @NotBlank
        @Schema(description = "Texto buscado.", example = "qual e o prazo de garantia do equipamento?")
        String text) {
}
