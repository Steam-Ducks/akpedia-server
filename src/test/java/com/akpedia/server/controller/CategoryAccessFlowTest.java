package com.akpedia.server.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.dto.EmbeddingModelInfo;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.EmbeddingRepository;
import com.akpedia.server.repository.SectorRepository;
import com.akpedia.server.repository.UserRepository;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.security.Viewer;
import com.akpedia.server.service.SectorPermissionService;

/**
 * The sector rule across every route that shows a document, on the real services, queries and
 * database: an employee whose sector sees one category of two never meets the other one -- not in
 * search, the recent list, the category catalog, nor by opening its id directly -- and an admin's
 * change applies from the next request.
 *
 * <p>Only who is logged in is replaced ({@link CurrentUser}), with the security filters off: the
 * token, roles and gates are {@code SecurityConfigTest}'s. The embedding service and Redis are
 * replaced as well. {@code @Transactional}, so everything a test creates is rolled back.
 *
 * <p>Its mocked beans make a Spring context of its own, which the test cache would keep open, pool
 * and all, for the rest of the run. Every cached context holds connections, and Postgres allows 100:
 * so this one keeps a small pool and is closed when the class ends.
 */
@SpringBootTest(properties = {
    "akpedia.processing.retry-interval=1h",
    "spring.datasource.hikari.maximum-pool-size=2"})
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@Transactional
class CategoryAccessFlowTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private SectorRepository sectors;
    @Autowired
    private UserRepository users;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentFileRepository documentFiles;
    @Autowired
    private EmbeddingRepository embeddings;
    @Autowired
    private SectorPermissionService sectorPermissions;

    @MockBean
    private CurrentUser currentUser;
    @MockBean
    private EmbeddingClient embeddingClient;
    @MockBean
    private StringRedisTemplate redis;

    /** Unique per test, so a search only ever meets this test's vectors. */
    private final String model = "modelo-de-teste-" + UUID.randomUUID();

    private Category visible;
    private Category blocked;
    private Sector sector;
    private Document visibleDocument;
    private Document blockedDocument;

    @BeforeEach
    void setUp() throws IOException {
        visible = categories.save(new Category("visivel-" + UUID.randomUUID(), null));
        blocked = categories.save(new Category("bloqueada-" + UUID.randomUUID(), null));

        sector = new Sector("setor-" + UUID.randomUUID(), null);
        sector.setCategories(new LinkedHashSet<>(List.of(visible)));
        sector = sectors.save(sector);
        User employee = users.save(new User("Funcionaria", UUID.randomUUID() + "@akpedia.test", sector));
        // Someone else uploaded both documents, so the author's exception never lets the employee in.
        User author = users.save(new User("Autor", UUID.randomUUID() + "@akpedia.test",
                sectors.save(new Sector("setor-" + UUID.randomUUID(), null))));

        byte[] pdf = Files.readAllBytes(Path.of("src/test/resources/manual-sample.pdf"));
        visibleDocument = indexed(visible, author, pdf);
        blockedDocument = indexed(blocked, author, pdf);

        given(currentUser.id()).willReturn(employee.getId());
        given(currentUser.viewer()).willReturn(new Viewer(employee.getId(), false));
        given(embeddingClient.embedQuery(anyString()))
                .willReturn(new QueryEmbeddingResponse(new EmbeddingModelInfo(model, Embedding.VECTOR_DIMENSIONS, true),
                        IntStream.range(0, Embedding.VECTOR_DIMENSIONS).mapToObj(i -> 1.0f).toList()));
    }

    /** An approved, indexed document, with one chunk identical to the query vector. */
    private Document indexed(Category category, User author, byte[] pdf) {
        Document document = new Document("manual-" + UUID.randomUUID() + ".pdf", "application/pdf",
                (long) pdf.length, category, author, DocumentStatus.APPROVED);
        document.setProcessingStatus(ProcessingStatus.COMPLETED);
        document = documents.save(document);
        documentFiles.save(new DocumentFile(document, pdf));
        float[] vector = new float[Embedding.VECTOR_DIMENSIONS];
        java.util.Arrays.fill(vector, 1.0f);
        embeddings.saveAndFlush(new Embedding(document, 0, "trecho do manual", vector, model, Embedding.VECTOR_DIMENSIONS));
        return document;
    }

    private ResultActions open(Document document, String path) throws Exception {
        return mvc.perform(get("/api/v1/documents/{id}" + path, document.getId()));
    }

    @Test
    @DisplayName("GET /categories lists only the categories the employee's sector sees")
    void catalogHoldsOnlyTheSectorsCategories() throws Exception {
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(visible.getId().intValue())))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(blocked.getId().intValue()))));
    }

    @Test
    @DisplayName("GET /documents lists only documents of the sector's categories")
    void recentListHoldsOnlyTheSectorsCategories() throws Exception {
        mvc.perform(get("/api/v1/documents").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].document_id").value(hasItem(visibleDocument.getId().intValue())))
                .andExpect(jsonPath("$[*].document_id").value(not(hasItem(blockedDocument.getId().intValue()))));
    }

    @Test
    @DisplayName("a text search returns only documents of the sector's categories")
    void textSearchHoldsOnlyTheSectorsCategories() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "manual"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].document_id").value(hasItem(visibleDocument.getId().intValue())))
                .andExpect(jsonPath("$[*].document_id").value(not(hasItem(blockedDocument.getId().intValue()))));
    }

    @Test
    @DisplayName("asking for both categories still searches only the visible one")
    void requestedCategoriesAreNarrowedToTheVisibleOnes() throws Exception {
        mvc.perform(get("/api/v1/search")
                        .param("categoryIds", visible.getId().toString(), blocked.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].document_id").value(hasItem(visibleDocument.getId().intValue())))
                .andExpect(jsonPath("$[*].document_id").value(not(hasItem(blockedDocument.getId().intValue()))));
    }

    @Test
    @DisplayName("asking only for the blocked category answers an empty list, without generating an embedding")
    void blockedCategoryAloneAnswersEmptyWithoutEmbedding() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "manual").param("categoryIds", blocked.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        then(embeddingClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("a direct link to the blocked document answers 403 category_forbidden on the file, the preview and its pages")
    void directLinkToTheBlockedDocumentIsRefused() throws Exception {
        for (String path : List.of("/file", "/preview", "/preview/pages/1")) {
            open(blockedDocument, path)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("category_forbidden"));
        }
    }

    @Test
    @DisplayName("the visible document opens on the file, the preview and its pages")
    void visibleDocumentOpens() throws Exception {
        for (String path : List.of("/file", "/preview", "/preview/pages/1")) {
            open(visibleDocument, path).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("once an admin gives the sector the blocked category, the next requests see it everywhere")
    void adminChangeAppliesFromTheNextRequest() throws Exception {
        open(blockedDocument, "/file").andExpect(status().isForbidden());

        sectorPermissions.setCategories(sector.getId(), List.of(visible.getId(), blocked.getId()));

        open(blockedDocument, "/file").andExpect(status().isOk());
        mvc.perform(get("/api/v1/search").param("q", "manual"))
                .andExpect(jsonPath("$[*].document_id").value(hasItem(blockedDocument.getId().intValue())));
        mvc.perform(get("/api/v1/documents").param("limit", "50"))
                .andExpect(jsonPath("$[*].document_id").value(hasItem(blockedDocument.getId().intValue())));
        mvc.perform(get("/api/v1/categories"))
                .andExpect(jsonPath("$[*].id").value(hasItem(blocked.getId().intValue())));
    }

    @Test
    @DisplayName("taking the category away closes it again from the next request")
    void revokedCategoryClosesFromTheNextRequest() throws Exception {
        open(visibleDocument, "/file").andExpect(status().isOk());

        sectorPermissions.setCategories(sector.getId(), List.of());

        open(visibleDocument, "/file")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("category_forbidden"));
        mvc.perform(get("/api/v1/search").param("q", "manual")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/documents")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/categories")).andExpect(jsonPath("$", hasSize(0)));
    }
}
