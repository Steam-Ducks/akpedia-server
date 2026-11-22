package com.akpedia.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.SectorCategories;
import com.akpedia.server.dto.SectorCategoryMatrix;
import com.akpedia.server.dto.UpdateSectorCategoriesRequest;
import com.akpedia.server.service.SectorPermissionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** The admin's permissions screen: which categories each sector may see. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Sector permissions", description = "Which categories each sector may see (ADMIN).")
public class SectorPermissionController {

    private static final String ERROR_SCHEMA = "application/json";

    private final SectorPermissionService permissions;

    public SectorPermissionController(SectorPermissionService permissions) {
        this.permissions = permissions;
    }

    @Operation(
            summary = "The sectors by categories matrix (ADMIN)",
            description = """
                    Every category (the columns, by name) and the realm's sectors (the rows, in the realm's
                    order), each with the ids of the categories it may see.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The matrix.")
    })
    @GetMapping("/sector-categories")
    public SectorCategoryMatrix matrix() {
        return permissions.matrix();
    }

    @Operation(
            summary = "Set the categories a sector may see (ADMIN)",
            description = """
                    `category_ids` lists every category the sector should see; the others are taken away. Applies
                    from the next search. Recorded as `SECTOR_CATEGORIES_CHANGED`, with the categories before and
                    after, when something changed.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The sector's row as it is now."),
        @ApiResponse(responseCode = "400", description = "`category_ids` is missing.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404",
                description = "`sector_not_found` or `category_not_found`: no such sector or category.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/sectors/{id}/categories")
    public SectorCategories setCategories(
            @PathVariable("id") Long id, @RequestBody @Valid UpdateSectorCategoriesRequest request) {
        return permissions.setCategories(id, request.categoryIds());
    }
}
