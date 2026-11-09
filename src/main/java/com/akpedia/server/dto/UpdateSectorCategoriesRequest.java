package com.akpedia.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;

/** @param categoryIds every category the sector should see; the others are taken away */
public record UpdateSectorCategoriesRequest(@JsonProperty("category_ids") @NotNull List<Long> categoryIds) {
}
