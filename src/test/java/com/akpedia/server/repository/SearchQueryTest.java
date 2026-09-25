package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.ProcessingStatus;
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

/**
 * Pins the columns the similarity query returns, and the order it returns them in.
 *
 * <p>A native query hands back an untyped {@code Object[]}, and {@code SearchService} reads it by
 * position. Adding a column in the middle of the {@code SELECT} would compile, pass every mocked
 * test, and then put the score where the snippet belongs -- this is the test that would catch it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SearchQueryTest {

    private static final String MODEL = "intfloat/multilingual-e5-small";
    private static final double ACCEPT_ANY_SCORE = -1.0;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EmbeddingRepository embeddingRepository;

    private Document document;

    @BeforeEach
    void setUp() {
        Sector sector = entityManager.persist(TestFixtures.sector());
        Category category = entityManager.persist(TestFixtures.category());
        User creator = entityManager.persist(TestFixtures.user(sector));

        document = TestFixtures.document(category, creator);
        document.setDescription("manual tecnico do time");
        document.setProcessingStatus(ProcessingStatus.COMPLETED);
        entityManager.persist(document);
        entityManager.persist(new Embedding(
                document, 4, "trecho que casou", TestFixtures.vector(0.1f), MODEL, Embedding.VECTOR_DIMENSIONS));
        entityManager.flush();
        entityManager.clear();
    }

    private static String queryVector() {
        return IntStream.range(0, Embedding.VECTOR_DIMENSIONS)
                .mapToObj(index -> String.valueOf(0.1f + index))
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    @DisplayName("a row carries id, name, description, mime type, chunk, chunk index and score, in that order")
    void rowCarriesEveryColumnInOrder() {
        List<Object[]> rows = embeddingRepository.search(
                queryVector(), MODEL, Embedding.VECTOR_DIMENSIONS, ACCEPT_ANY_SCORE, 10);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row).hasSize(7);
        assertThat(((Number) row[0]).longValue()).isEqualTo(document.getId());
        assertThat(row[1]).isEqualTo(document.getName());
        assertThat(row[2]).isEqualTo("manual tecnico do time");
        assertThat(row[3]).isEqualTo("application/pdf");
        assertThat(row[4]).isEqualTo("trecho que casou");
        assertThat(((Number) row[5]).intValue()).isEqualTo(4);
        assertThat(((Number) row[6]).doubleValue()).isBetween(-1.0, 1.0);
    }

    @Test
    @DisplayName("the score of an exact match is 1, so the column really is the similarity")
    void scoreOfAnIdenticalVectorIsOne() {
        List<Object[]> rows = embeddingRepository.search(
                queryVector(), MODEL, Embedding.VECTOR_DIMENSIONS, ACCEPT_ANY_SCORE, 10);

        assertThat(((Number) rows.get(0)[6]).doubleValue()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }
}
