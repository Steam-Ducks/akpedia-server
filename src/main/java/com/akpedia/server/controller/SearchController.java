package com.akpedia.server.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.SearchResult;
import com.akpedia.server.service.SearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Search", description = "Semantic search across indexed documents.")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(
            summary = "Search documents by semantic proximity",
            description = "Accepts a word, phrase, or question and returns at most one result per document.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Results ordered by relevance; the list may be empty."),
        @ApiResponse(responseCode = "400", description = "The query or limit is invalid."),
        @ApiResponse(responseCode = "502", description = "The embedding service returned an invalid response."),
        @ApiResponse(responseCode = "503", description = "The embedding service is unavailable.")
    })
    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam String q,
            @RequestParam(required = false) Integer limit) {
        return searchService.search(q, limit);
    }
}
