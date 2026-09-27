package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the viewer needs to lay out a document before asking for any of its pages.
 *
 * @param documentId document being viewed
 * @param name       name to show above the pages
 * @param pageCount  how many pages the viewer may ask for, counted from 1
 */
public record DocumentPreview(
        @JsonProperty("document_id") Long documentId,
        String name,
        @JsonProperty("page_count") int pageCount) {
}
