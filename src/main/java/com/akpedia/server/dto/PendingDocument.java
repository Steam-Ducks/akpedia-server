package com.akpedia.server.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;

/**
 * A document as the review queue shows it: what the approver needs to pick it up and decide.
 *
 * @param id                identifier the document is reached by on the other document routes,
 *                          its file included
 * @param name              document title
 * @param description       description the author sent, often the one akpedia-ml suggested;
 *                          null when the upload carried none
 * @param creatorName       name of the user who uploaded the document
 * @param sectorName        sector of that user
 * @param createdAt         when the document was uploaded, which is what orders the queue
 * @param category          category the author chose, which may differ from the suggestion
 * @param suggestedCategory category the classifier suggested; null when the upload carried none
 * @param confidence        how sure the classifier was of that suggestion, from 0 to 1; null when
 *                          there is no suggestion
 */
public record PendingDocument(
        Long id,
        String name,
        String description,
        @JsonProperty("creator_name") String creatorName,
        @JsonProperty("sector_name") String sectorName,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        CategoryRef category,
        @JsonProperty("suggested_category") CategoryRef suggestedCategory,
        BigDecimal confidence) {

    /**
     * A category, carrying its id so the approver can confirm it as is.
     *
     * @param id   category identifier
     * @param name category name
     */
    public record CategoryRef(Long id, String name) {

        static CategoryRef of(Category category) {
            return category != null ? new CategoryRef(category.getId(), category.getName()) : null;
        }
    }

    public static PendingDocument from(Document document) {
        return new PendingDocument(
                document.getId(),
                document.getName(),
                document.getDescription(),
                document.getCreator().getName(),
                document.getCreator().getSector().getName(),
                document.getCreatedAt(),
                CategoryRef.of(document.getCategory()),
                CategoryRef.of(document.getSuggestedCategory()),
                document.getCategoryConfidence());
    }
}
