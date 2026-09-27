package com.akpedia.server.controller;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.dto.SearchResult;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.EmbeddingUnavailableException;
import com.akpedia.server.exception.InvalidSearchRequestException;
import com.akpedia.server.service.SearchService;

/**
 * Pins the JSON shape of a search result, which is the contract an interface is built against.
 *
 * <p>The field names live in {@code SearchResult} as {@code @JsonProperty} annotations, so a rename
 * or a dropped field compiles fine and only breaks whoever is consuming the route. These assertions
 * are what turns that into a failing build instead.
 */
@WebMvcTest(controllers = SearchController.class)
@Import(ApiExceptionHandler.class)
class SearchControllerTest {

    @Autowired
    private MockMvc mvc;

    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.of(2026, 9, 20, 14, 30, 0, 0, ZoneOffset.UTC);

    @MockBean
    private SearchService searchService;

    private static SearchResult result() {
        return new SearchResult(
                7L, "manual-de-integracao.pdf", "manual tecnico do time", "application/pdf",
                0.913, "trecho que casou com a busca…", 3,
                "Técnica", "Mariana Costa", UPDATED_AT);
    }

    @Test
    @DisplayName("GET /api/v1/search answers each result with the ten agreed fields")
    void searchResultCarriesTheAgreedFormat() throws Exception {
        given(searchService.search("integracao", null)).willReturn(List.of(result()));

        mvc.perform(get("/api/v1/search").param("q", "integracao"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].document_id").value(7))
                .andExpect(jsonPath("$[0].name").value("manual-de-integracao.pdf"))
                .andExpect(jsonPath("$[0].description").value("manual tecnico do time"))
                .andExpect(jsonPath("$[0].mime_type").value("application/pdf"))
                .andExpect(jsonPath("$[0].score").value(0.913))
                .andExpect(jsonPath("$[0].matched_chunk").value("trecho que casou com a busca…"))
                .andExpect(jsonPath("$[0].chunk_index").value(3))
                .andExpect(jsonPath("$[0].category").value("Técnica"))
                .andExpect(jsonPath("$[0].responsible_name").value("Mariana Costa"))
                .andExpect(jsonPath("$[0].updated_at").value("2026-09-20T14:30:00Z"));
    }

    @Test
    @DisplayName("the result carries those fields and nothing else, so the contract stays readable")
    void searchResultCarriesNoOtherField() throws Exception {
        given(searchService.search("integracao", null)).willReturn(List.of(result()));

        mvc.perform(get("/api/v1/search").param("q", "integracao"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].*", hasSize(10)))
                // The snake_case spelling is the contract: a camelCase key means an annotation was lost.
                .andExpect(jsonPath("$", everyItem(hasKey("document_id"))))
                .andExpect(jsonPath("$", everyItem(hasKey("mime_type"))))
                .andExpect(jsonPath("$", everyItem(hasKey("matched_chunk"))))
                .andExpect(jsonPath("$", everyItem(hasKey("chunk_index"))))
                .andExpect(jsonPath("$", everyItem(hasKey("responsible_name"))))
                .andExpect(jsonPath("$", everyItem(hasKey("updated_at"))));
    }

    @Test
    @DisplayName("a document uploaded without a description keeps the field, as null")
    void descriptionIsNullRatherThanAbsent() throws Exception {
        given(searchService.search("integracao", null)).willReturn(List.of(new SearchResult(
                7L, "manual.pdf", null, "application/pdf", 0.9, "trecho", 0,
                "Técnica", "Mariana Costa", UPDATED_AT)));

        mvc.perform(get("/api/v1/search").param("q", "integracao"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value(nullValue()))
                .andExpect(jsonPath("$[0].*", hasSize(10)));
    }

    @Test
    @DisplayName("a search with no match answers 200 and an empty list, not 404")
    void noMatchIsAnEmptyList() throws Exception {
        given(searchService.search("inexistente", null)).willReturn(List.of());

        mvc.perform(get("/api/v1/search").param("q", "inexistente"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("the result list is JSON, whatever the caller asks to accept")
    void responseIsJson() throws Exception {
        given(searchService.search("integracao", null)).willReturn(List.of(result()));

        mvc.perform(get("/api/v1/search").param("q", "integracao").accept(MediaType.ALL))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("a missing q answers 400, not 500")
    void missingQueryBecomes400() throws Exception {
        mvc.perform(get("/api/v1/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    @DisplayName("an invalid limit answers 400 with the reason")
    void invalidLimitBecomes400() throws Exception {
        willThrow(new InvalidSearchRequestException("limit must be between 1 and 50."))
                .given(searchService).search(any(), any());

        mvc.perform(get("/api/v1/search").param("q", "integracao").param("limit", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 50."));
    }

    @Test
    @DisplayName("the embedding service being down answers 503, not 500")
    void embeddingServiceDownBecomes503() throws Exception {
        willThrow(new EmbeddingUnavailableException("akpedia-ml esta fora do ar", null))
                .given(searchService).search(any(), any());

        mvc.perform(get("/api/v1/search").param("q", "integracao"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("embedding_service_unavailable"));
    }
}
