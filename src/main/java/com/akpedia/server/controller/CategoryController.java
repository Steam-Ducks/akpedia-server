package com.akpedia.server.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.CategoryResponse;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.CategoryAccessService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Categories", description = "Document category catalog, used to populate the search filter.")
public class CategoryController {

    private final CategoryAccessService categoryAccess;
    private final CurrentUser currentUser;

    public CategoryController(CategoryAccessService categoryAccess, CurrentUser currentUser) {
        this.categoryAccess = categoryAccess;
        this.currentUser = currentUser;
    }

    @Operation(
            summary = "List the categories the caller may see",
            description = """
                    The categories the caller's sector may see, by name, with no paging: a client uses it to
                    populate the category filter on `GET /search`, which is why it answers every one of them
                    instead of a page. Read from the sector's permissions on every request, so a category an
                    admin takes from the sector disappears from the next call; a sector with none answers an
                    empty list. The whole catalog is on the admin's `GET /admin/sector-categories`.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The categories the caller's sector may see, ordered by name.")
    })
    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return categoryAccess.allowedCategories(currentUser.id()).stream().map(CategoryResponse::from).toList();
    }
}
