package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecentDocumentsQueryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private EmbeddingRepository embeddingRepository;

    private Category category;
    private User creator;

    private Document persist(ProcessingStatus processingStatus, DocumentStatus status) {
        Document document = TestFixtures.document(category, creator);
        document.setProcessingStatus(processingStatus);
        document.setStatus(status);
        return entityManager.persistAndFlush(document);
    }

    /**
     * The ids of the recent documents, among the ones this test created. The database may already
     * hold other documents (the local dev database usually does), so only the test's own count.
     */
    private List<Long> recentIdsAmong(List<Document> created) {
        List<Long> ids = created.stream().map(Document::getId).toList();
        return documentRepository
                .findRecent(ProcessingStatus.COMPLETED, DocumentStatus.ARCHIVED, PageRequest.of(0, 1000))
                .stream()
                .map(Document::getId)
                .filter(ids::contains)
                .toList();
    }

    @Test
    @DisplayName("only indexed, not archived documents are listed, the most recent first")
    void listsOpenableDocumentsMostRecentFirst() {
        category = entityManager.persist(TestFixtures.category());
        creator = entityManager.persist(TestFixtures.user(entityManager.persist(TestFixtures.sector())));

        Document older = persist(ProcessingStatus.COMPLETED, DocumentStatus.DRAFT);
        Document archived = persist(ProcessingStatus.COMPLETED, DocumentStatus.ARCHIVED);
        Document pending = persist(ProcessingStatus.PENDING, DocumentStatus.DRAFT);
        Document newer = persist(ProcessingStatus.COMPLETED, DocumentStatus.APPROVED);
        entityManager.clear();

        assertThat(recentIdsAmong(List.of(older, archived, pending, newer)))
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("category and creator come loaded with the document")
    void fetchesCategoryAndCreator() {
        category = entityManager.persist(TestFixtures.category());
        creator = entityManager.persist(TestFixtures.user(entityManager.persist(TestFixtures.sector())));
        Document document = persist(ProcessingStatus.COMPLETED, DocumentStatus.DRAFT);
        entityManager.clear();

        Document found = documentRepository
                .findRecent(ProcessingStatus.COMPLETED, DocumentStatus.ARCHIVED, PageRequest.of(0, 1000))
                .stream()
                .filter(candidate -> candidate.getId().equals(document.getId()))
                .findFirst()
                .orElseThrow();
        entityManager.clear();

        assertThat(found.getCategory().getName()).isEqualTo(category.getName());
        assertThat(found.getCreator().getName()).isEqualTo(creator.getName());
    }

    @Test
    @DisplayName("the first chunk of each document is its excerpt, and a document with no chunks has none")
    void firstChunkOfEachDocument() {
        category = entityManager.persist(TestFixtures.category());
        creator = entityManager.persist(TestFixtures.user(entityManager.persist(TestFixtures.sector())));
        Document indexed = persist(ProcessingStatus.COMPLETED, DocumentStatus.DRAFT);
        Document empty = persist(ProcessingStatus.COMPLETED, DocumentStatus.DRAFT);
        String model = "intfloat/multilingual-e5-small";
        // Persisted out of order, so the query has to pick by chunk_index, not insertion.
        entityManager.persist(new Embedding(
                indexed, 1, "segundo trecho", TestFixtures.vector(0.2f), model, Embedding.VECTOR_DIMENSIONS));
        entityManager.persist(new Embedding(
                indexed, 0, "primeiro trecho", TestFixtures.vector(0.1f), model, Embedding.VECTOR_DIMENSIONS));
        entityManager.flush();
        entityManager.clear();

        List<Object[]> rows = embeddingRepository.findFirstChunks(List.of(indexed.getId(), empty.getId()));

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(indexed.getId());
        assertThat(rows.get(0)[1]).isEqualTo("primeiro trecho");
    }
}
