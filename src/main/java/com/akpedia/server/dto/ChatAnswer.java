package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What akpedia-ml answers on {@code POST /api/v1/chat/answer}.
 *
 * @param answer  the short answer, each statement tagged with the number of the passage it came from
 * @param found   {@code false} when the passages do not answer the question
 * @param sources the {@code documentId} of each document the answer cites, in order of first
 *                citation, without repeats; empty when {@code found} is {@code false}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatAnswer(String answer, Boolean found, List<String> sources) {
}
