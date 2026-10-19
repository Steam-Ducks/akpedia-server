package com.akpedia.server.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.entity.Category;
import com.akpedia.server.repository.CategoryRepository;

@WebMvcTest(controllers = CategoryController.class)
class CategoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CategoryRepository categories;

    @Test
    @DisplayName("GET /api/v1/categories lists every category, ordered by name")
    void listsEveryCategoryOrderedByName() throws Exception {
        Category engenharia = new Category("Engenharia", "documentos de engenharia");
        Category tecnica = new Category("Tecnica", null);
        given(categories.findAllByOrderByNameAsc()).willReturn(List.of(engenharia, tecnica));

        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Engenharia"))
                .andExpect(jsonPath("$[0].description").value("documentos de engenharia"))
                .andExpect(jsonPath("$[1].name").value("Tecnica"))
                .andExpect(jsonPath("$[1].description").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("an empty catalog answers 200 with an empty list, not 404")
    void emptyCatalogIsAnEmptyList() throws Exception {
        given(categories.findAllByOrderByNameAsc()).willReturn(List.of());

        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }
}
