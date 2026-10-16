package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * The suggestion the upload screen gets back before it uploads anything.
 *
 * <p>Carries the category id, not the name, because that is what {@code POST /api/v1/documents}
 * takes as {@code suggestedCategoryId}: the screen can hand this straight back without having to
 * look anything up.
 *
 * @param suggestedCategoryId category the classifier suggested, as a {@code categories} id
 * @param confidence          how sure the classifier is, from 0 to 1
 */
public record DocumentAnalysisResponse(
        @JsonProperty("suggested_category_id") Long suggestedCategoryId,
        BigDecimal confidence) {
}
