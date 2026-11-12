package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;

/**
 * Pins {@link EmbeddingRepository#findTopChunks}: which chunks it lets through, in what order, and
 * the columns {@code ChatRetrievalService} reads by position.
 *
 * <p>Every test narrows the query to a category of its own, so the documents a local database
 * already holds never come back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatPassagesQueryTest {

    private static final String MODEL = "intfloat/multilingual-e5-small";
    private static final double ACCEPT_ANY_SCORE = -1.0;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EmbeddingRepository embeddingRepository;

    private Category category;
    private Category otherCategory;
    private User creator;

    @BeforeEach
    void setUp() {
        Sector sector = entityManager.persist(TestFixtures.sector());
        category = entityManager.persist(TestFixtures.category());
        otherCategory = entityManager.persist(TestFixtures.category());
        creator = entityManager.persist(TestFixtures.user(sector));
    }

    private Document document(Category in, DocumentStatus status, ProcessingStatus processing) {
        Document document = TestFixtures.document(in, creator);
        document.setStatus(status);
        document.setProcessingStatus(processing);
        return entityManager.persist(document);
    }

    private Document searchable() {
        return document(category, DocumentStatus.APPROVED, ProcessingStatus.COMPLETED);
    }

    /** A vector pointing mostly at {@code axis}: the further two axes, the less similar. */
    private static float[] towards(int axis) {
        float[] values = new float[Embedding.VECTOR_DIMENSIONS];
        values[axis] = 1f;
        values[Embedding.VECTOR_DIMENSIONS - 1] = 0.1f;
        return values;
    }

    private static String queryTowards(int axis) {
        float[] values = towards(axis);
        return IntStream.range(0, values.length)
                .mapToObj(index -> String.valueOf(values[index]))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private void chunk(Document document, int index, String text, float[] vector) {
        entityManager.persist(new Embedding(document, index, text, vector, MODEL, Embedding.VECTOR_DIMENSIONS));
    }

    private List<Object[]> topChunks(double minimumScore, int limit, List<Long> categoryIds) {
        entityManager.flush();
        entityManager.clear();
        return embeddingRepository.findTopChunks(
                queryTowards(0), MODEL, Embedding.VECTOR_DIMENSIONS, minimumScore, categoryIds, limit);
    }

    @Test
    @DisplayName("a row carries content, document id, name, category, score and chunk index, in that order")
    void rowCarriesEveryColumnInOrder() {
        Document document = searchable();
        chunk(document, 3, "o reembolso sai em 7 dias", towards(0));

        List<Object[]> rows = topChunks(ACCEPT_ANY_SCORE, 10, List.of(category.getId()));

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        assertThat(row).hasSize(6);
        assertThat(row[0]).isEqualTo("o reembolso sai em 7 dias");
        assertThat(((Number) row[1]).longValue()).isEqualTo(document.getId());
        assertThat(row[2]).isEqualTo(document.getName());
        assertThat(row[3]).isEqualTo(category.getName());
        assertThat(((Number) row[4]).doubleValue()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(((Number) row[5]).intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("several chunks of one document come back, closest first, up to the limit")
    void severalChunksOfOneDocumentClosestFirst() {
        Document document = searchable();
        chunk(document, 0, "longe", towards(5));
        chunk(document, 1, "perto", towards(0));
        float[] near = towards(0);
        near[1] = 0.3f;
        chunk(document, 2, "quase", near);

        List<Object[]> rows = topChunks(ACCEPT_ANY_SCORE, 2, List.of(category.getId()));

        assertThat(rows).extracting(row -> row[0]).containsExactly("perto", "quase");
    }

    @Test
    @DisplayName("chunks below the minimum score are left out")
    void belowTheMinimumScoreIsLeftOut() {
        Document document = searchable();
        chunk(document, 0, "perto", towards(0));
        chunk(document, 1, "longe", towards(5));

        List<Object[]> rows = topChunks(0.9, 10, List.of(category.getId()));

        assertThat(rows).extracting(row -> row[0]).containsExactly("perto");
    }

    @Test
    @DisplayName("a valid chunk comes back even behind many closer ones the user may not see, index or not")
    void validChunkBehindManyForbiddenOnesComesBack() {
        // More than hnsw.ef_search (40) closer chunks, all in a category the user does not see: an
        // index scan filtering afterwards would hand back none of the valid one.
        Document forbidden = document(otherCategory, DocumentStatus.APPROVED, ProcessingStatus.COMPLETED);
        for (int i = 0; i < 60; i++) {
            chunk(forbidden, i, "proibido " + i, towards(0));
        }
        float[] farther = towards(0);
        farther[1] = 0.6f;
        chunk(searchable(), 0, "permitido", farther);
        entityManager.flush();
        // Pushes the planner towards the index, as a large table would.
        entityManager.getEntityManager().createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();

        List<Object[]> rows = embeddingRepository.findTopChunks(
                queryTowards(0), MODEL, Embedding.VECTOR_DIMENSIONS, ACCEPT_ANY_SCORE, List.of(category.getId()), 5);

        assertThat(rows).extracting(row -> row[0]).containsExactly("permitido");
    }

    @Test
    @DisplayName("only approved, indexed documents in the given categories take part")
    void onlyApprovedIndexedDocumentsOfTheGivenCategories() {
        chunk(searchable(), 0, "aprovado", towards(0));
        chunk(document(category, DocumentStatus.PENDING_APPROVAL, ProcessingStatus.COMPLETED), 0, "em revisao", towards(0));
        chunk(document(category, DocumentStatus.REJECTED, ProcessingStatus.COMPLETED), 0, "devolvido", towards(0));
        chunk(document(category, DocumentStatus.APPROVED, ProcessingStatus.PROCESSING), 0, "indexando", towards(0));
        chunk(document(otherCategory, DocumentStatus.APPROVED, ProcessingStatus.COMPLETED), 0, "outra categoria", towards(0));

        List<Object[]> rows = topChunks(ACCEPT_ANY_SCORE, 10, List.of(category.getId()));

        assertThat(rows).extracting(row -> row[0]).containsExactly("aprovado");
    }
}
