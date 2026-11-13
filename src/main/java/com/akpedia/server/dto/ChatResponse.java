package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The chat's answer to a question.
 *
 * @param answer  short answer in Portuguese, each statement tagged with a passage number ({@code [1]});
 *                when nothing was found, a sentence saying so
 * @param found   {@code false} when no document the user may see answers the question
 * @param sources the documents the answer cites, in order of first citation; empty when not found
 */
public record ChatResponse(String answer, boolean found, List<Source> sources) {

    /**
     * One cited document, with what is needed to show it and open it.
     *
     * @param documentId the id the document is opened by on the other document routes
     * @param name       the document's name
     * @param category   the name of its category
     */
    public record Source(@JsonProperty("document_id") Long documentId, String name, String category) {
    }
}
