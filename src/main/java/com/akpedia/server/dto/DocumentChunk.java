package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One chunk of a document and its numeric representation.
 *
 * @param index     position of the chunk in the document, starting at 0
 * @param text      chunk text, as extracted
 * @param embedding normalized vector of the chunk
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentChunk(int index, String text, List<Float> embedding) {
}
