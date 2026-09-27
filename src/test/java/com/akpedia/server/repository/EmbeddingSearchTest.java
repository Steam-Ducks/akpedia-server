package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
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
 * Which documents the similarity query considers eligible.
 *
 * <p>Scoring is left out on purpose: {@code minimumScore} is dropped to {@code -1} so every chunk
 * clears it, and what each case asserts is the filtering -- a search result that cannot be opened
 * afterwards is a broken link in the interface, so the query has to leave those documents out.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmbeddingSearchTest {

    private static final String MODEL = "intfloat/multilingual-e5-small";
    private static final double ACCEPT_ANY_SCORE = -1.0;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EmbeddingRepository embeddingRepository;

    private Category category;
    private User creator;

    @BeforeEach
    void setUp() {
        Sector sector = entityManager.persist(TestFixtures.sector());
        category = entityManager.persist(TestFixtures.category());
        creator = entityManager.persist(TestFixtures.user(sector));
        entityManager.flush();
    }

    /** Persists a document in the given state, with one chunk the query can match. */
    private Document indexedDocument(DocumentStatus status, ProcessingStatus processingStatus) {
        Document document = TestFixtures.document(category, creator);
        document.setStatus(status);
        document.setProcessingStatus(processingStatus);
        entityManager.persist(document);
        entityManager.persist(new Embedding(
                document, 0, "trecho do manual", TestFixtures.vector(0.1f), MODEL, Embedding.VECTOR_DIMENSIONS));
        entityManager.flush();
        return document;
    }

    private List<Long> search() {
        entityManager.clear();
        return embeddingRepository.search(
                        queryVector(), MODEL, Embedding.VECTOR_DIMENSIONS, ACCEPT_ANY_SCORE, 10)
                .stream()
                .map(row -> ((Number) row[0]).longValue())
                .toList();
    }

    private static String queryVector() {
        return IntStream.range(0, Embedding.VECTOR_DIMENSIONS)
                .mapToObj(i -> String.valueOf(0.1f + i))
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    @DisplayName("an indexed document in circulation is returned")
    void indexedDocumentIsReturned() {
        Document document = indexedDocument(DocumentStatus.APPROVED, ProcessingStatus.COMPLETED);

        assertThat(search()).contains(document.getId());
    }

    @Test
    @DisplayName("an archived document is left out: its file answers 403, so the result would not open")
    void archivedDocumentIsLeftOut() {
        Document eligible = indexedDocument(DocumentStatus.APPROVED, ProcessingStatus.COMPLETED);
        Document archived = indexedDocument(DocumentStatus.ARCHIVED, ProcessingStatus.COMPLETED);

        // The eligible document is asserted alongside so an empty result cannot pass for a filter.
        assertThat(search()).contains(eligible.getId()).doesNotContain(archived.getId());
    }

    @Test
    @DisplayName("a document that is not COMPLETED is left out, whatever its approval status")
    void documentNotIndexedIsLeftOut() {
        Document eligible = indexedDocument(DocumentStatus.APPROVED, ProcessingStatus.COMPLETED);
        Document pending = indexedDocument(DocumentStatus.APPROVED, ProcessingStatus.PENDING);
        Document failed = indexedDocument(DocumentStatus.APPROVED, ProcessingStatus.FAILED);

        assertThat(search()).contains(eligible.getId()).doesNotContain(pending.getId(), failed.getId());
    }

    @Test
    @DisplayName("a document with several chunks is returned once")
    void oneRowPerDocument() {
        Document document = indexedDocument(DocumentStatus.APPROVED, ProcessingStatus.COMPLETED);
        entityManager.persist(new Embedding(
                document, 1, "outro trecho", TestFixtures.vector(0.2f), MODEL, Embedding.VECTOR_DIMENSIONS));
        entityManager.flush();

        assertThat(search()).containsOnlyOnce(document.getId());
    }

}
