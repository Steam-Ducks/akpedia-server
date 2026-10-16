package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * The suggestions the upload screen gets back before it uploads anything.
 *
 * <p>Carries the category id, not the name, because that is what {@code POST /api/v1/documents}
 * takes as {@code suggestedCategoryId}: the screen can hand this straight back without having to
 * look anything up.
 *
 * <p>{@code suggestedCategoryId} and {@code confidence} are both {@code null} when the classifier
 * has no suggestion; the screen then leaves the category for the user to pick. The title and
 * description are suggested either way.
 *
 * @param title               suggested title, read from the document
 * @param description         suggested description, or {@code null} when the document has none
 * @param suggestedCategoryId category the classifier suggested, as a {@code categories} id, or {@code null}
 * @param confidence          how sure the classifier is, from 0 to 1, or {@code null}
 */
public record DocumentAnalysisResponse(
        String title,
        String description,
        @JsonProperty("suggested_category_id") Long suggestedCategoryId,
        BigDecimal confidence) {
}
