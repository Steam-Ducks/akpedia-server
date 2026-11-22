package com.akpedia.server.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.entity.Category;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.CategoryAccessService;

@WebMvcTest(controllers = CategoryController.class)
// The endpoint is under test, not who may call it: SecurityConfigTest covers that.
@AutoConfigureMockMvc(addFilters = false)
class CategoryControllerTest {

    private static final long USER_ID = 20L;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CategoryAccessService categoryAccess;

    @MockBean
    private CurrentUser currentUser;

    @BeforeEach
    void logIn() {
        given(currentUser.id()).willReturn(USER_ID);
    }

    @Test
    @DisplayName("GET /api/v1/categories lists the categories the caller's sector sees, ordered by name")
    void listsTheSectorsCategoriesOrderedByName() throws Exception {
        Category engenharia = new Category("Engenharia", "documentos de engenharia");
        Category tecnica = new Category("Tecnica", null);
        given(categoryAccess.allowedCategories(USER_ID)).willReturn(List.of(engenharia, tecnica));

        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Engenharia"))
                .andExpect(jsonPath("$[0].description").value("documentos de engenharia"))
                .andExpect(jsonPath("$[1].name").value("Tecnica"))
                .andExpect(jsonPath("$[1].description").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("a sector that sees no category answers 200 with an empty list, not 404")
    void sectorWithNoCategoryIsAnEmptyList() throws Exception {
        given(categoryAccess.allowedCategories(USER_ID)).willReturn(List.of());

        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }
}
