package com.akpedia.server.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.CategoryResponse;
import com.akpedia.server.repository.CategoryRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Categories", description = "Document category catalog, used to populate the search filter.")
public class CategoryController {

    private final CategoryRepository categories;

    public CategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @Operation(
            summary = "List every category",
            description = """
                    The whole catalog, by name, with no paging: a client uses it to populate the category filter
                    on `GET /search`, which is why it answers every category instead of a page of them.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Every category, ordered by name.")
    })
    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return categories.findAllByOrderByNameAsc().stream().map(CategoryResponse::from).toList();
    }
}
