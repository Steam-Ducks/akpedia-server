package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.akpedia.server.entity.Document;

/**
 * A document as a list of documents shows it: the same fields a search result carries about the
 * document, without the match.
 *
 * @param documentId      identifier the document is reached by on the other document routes
 * @param name            document title
 * @param description     optional description given at upload
 * @param mimeType        format of the stored file
 * @param category        name of the category the document is filed under
 * @param responsibleName name of the user who uploaded the document
 * @param updatedAt       when the document last changed; its creation time if it never has
 * @param excerpt         the start of the document's text, trimmed like a search snippet; null
 *                        when the document has no indexed text
 */
public record DocumentSummary(
        @JsonProperty("document_id") Long documentId,
        String name,
        String description,
        @JsonProperty("mime_type") String mimeType,
        String category,
        @JsonProperty("responsible_name") String responsibleName,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        String excerpt) {

    public static DocumentSummary from(Document document, String excerpt) {
        return new DocumentSummary(
                document.getId(),
                document.getName(),
                document.getDescription(),
                document.getMimeType(),
                document.getCategory().getName(),
                document.getCreator().getName(),
                document.getUpdatedAt() != null ? document.getUpdatedAt() : document.getCreatedAt(),
                excerpt);
    }
}
