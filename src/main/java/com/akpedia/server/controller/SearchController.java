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
            summary = "Search documents, by semantic proximity to text, by category, or both",
            description = """
                    Accepts a word, phrase, or question and returns at most one result per document. With `q`,
                    results are ordered by relevance; only documents akpedia-ml has indexed take part, and a chunk
                    below the configured minimum score is left out, so a search with nothing close answers 200
                    with an empty list. `categoryIds` narrows results to one or more categories, and can be used
                    on its own: without `q`, results are ordered by when the document last changed, most recent
                    first, and `score` comes back null. At least one of `q` and `categoryIds` is required.

                    Each result identifies the document, names it, gives its format, and carries the chunk that
                    matched together with that chunk's position in the document, counted from 0 -- or, with no
                    `q`, the document's first chunk, standing in as an excerpt. The chunk comes trimmed to
                    `akpedia.search.snippet-length` characters (300 by default, ellipsis included): runs of
                    whitespace collapse into single spaces and the cut lands on the last whole word that fits.

                    A result also carries what a result card shows about the document: its `category`, the
                    `responsible_name` of the user who uploaded it, and `updated_at`, when it last changed --
                    its creation time if it never has.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Results ordered by relevance or recency; the list may be empty."),
        @ApiResponse(responseCode = "400", description = "Neither q nor categoryIds was given, or the limit is invalid."),
        @ApiResponse(responseCode = "502", description = "The embedding service returned an invalid response."),
        @ApiResponse(responseCode = "503", description = "The embedding service is unavailable.")
    })
    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<Long> categoryIds,
            @RequestParam(required = false) Integer limit) {
        return searchService.search(q, categoryIds, limit);
    }
}
