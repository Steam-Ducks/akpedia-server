package com.akpedia.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A question asked in the chat.
 *
 * @param question what the user asked; refused here when blank or too long, before any call to akpedia-ml
 */
public record ChatRequest(
        @NotBlank
        @Size(max = ChatRequest.MAX_QUESTION_LENGTH)
        @Schema(description = "The question.", example = "Qual o prazo de reembolso de despesas?")
        String question) {

    /** A question, not a document: past this, it is pasted text the model would answer badly anyway. */
    public static final int MAX_QUESTION_LENGTH = 1000;
}
