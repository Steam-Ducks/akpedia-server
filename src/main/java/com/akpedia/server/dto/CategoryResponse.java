package com.akpedia.server.dto;

import com.akpedia.server.entity.Category;

/**
 * A document category, as the search filter lists them.
 *
 * @param id          identifier used as a {@code categoryIds} value on {@code GET /search}
 * @param name        category name
 * @param description optional description, or null when the category has none
 */
public record CategoryResponse(Long id, String name, String description) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getDescription());
    }
}
