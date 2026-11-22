package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One sector's row in the permissions matrix.
 *
 * @param categoryIds the categories its users may see, by id
 */
public record SectorCategories(
        Long id,
        String name,
        @JsonProperty("category_ids") List<Long> categoryIds) {
}
