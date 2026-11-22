package com.akpedia.server.dto;

import java.util.List;

/**
 * Which categories each sector may see.
 *
 * @param categories every category, by name: the matrix's columns
 * @param sectors    the realm's sectors, in the realm's order: its rows
 */
public record SectorCategoryMatrix(List<CategoryResponse> categories, List<SectorCategories> sectors) {
}
