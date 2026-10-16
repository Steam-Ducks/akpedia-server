package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;

/**
 * The review queue query.
 *
 * <p>The database may already hold pending documents (the local dev database usually does), so
 * each test document is backdated to the year 2000: that puts the test's own documents at the head
 * of an oldest-first queue, where the first pages can be asserted exactly.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PendingDocumentsQueryTest {

    private static final OffsetDateTime Y2K = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DocumentRepository documentRepository;

    private Category category;
    private User creator;

    @BeforeEach
    void setUp() {
        category = entityManager.persist(TestFixtures.category());
        creator = entityManager.persist(TestFixtures.user(entityManager.persist(TestFixtures.sector())));
    }

    /** A document in {@code status}, created {@code hoursAfterY2k} hours into the year 2000. */
    private Document persist(DocumentStatus status, int hoursAfterY2k) {
        Document document = TestFixtures.document(category, creator);
        document.setStatus(status);
        entityManager.persistAndFlush(document);
        // created_at is stamped by Hibernate on insert, so it is backdated in the row afterwards.
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE documents SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", Y2K.plusHours(hoursAfterY2k))
                .setParameter("id", document.getId())
                .executeUpdate();
        return document;
    }

    private Page<Document> pendingPage(int page, int size) {
        entityManager.clear();
        return documentRepository.findOldestFirstByStatus(DocumentStatus.PENDING_APPROVAL, PageRequest.of(page, size));
    }

    @Test
    @DisplayName("only pending documents are queued, the oldest first, ties broken by id")
    void queuesPendingDocumentsOldestFirst() {
        Document approved = persist(DocumentStatus.APPROVED, 1);
        Document second = persist(DocumentStatus.PENDING_APPROVAL, 3);
        Document thirdTied = persist(DocumentStatus.PENDING_APPROVAL, 4);
        Document fourthTied = persist(DocumentStatus.PENDING_APPROVAL, 4);
        Document draft = persist(DocumentStatus.DRAFT, 2);
        // Persisted last but created first: the queue follows created_at, not insertion.
        Document first = persist(DocumentStatus.PENDING_APPROVAL, 2);

        assertThat(pendingPage(0, 4).getContent())
                .extracting(Document::getId)
                .containsExactly(first.getId(), second.getId(), thirdTied.getId(), fourthTied.getId())
                .doesNotContain(approved.getId(), draft.getId());
    }

    @Test
    @DisplayName("pages split the queue in order, and the totals count every pending document")
    void pagesThroughTheQueue() {
        Document first = persist(DocumentStatus.PENDING_APPROVAL, 1);
        Document second = persist(DocumentStatus.PENDING_APPROVAL, 2);
        Document third = persist(DocumentStatus.PENDING_APPROVAL, 3);
        persist(DocumentStatus.APPROVED, 0);

        Page<Document> firstPage = pendingPage(0, 2);
        Page<Document> secondPage = pendingPage(1, 2);

        assertThat(firstPage.getContent()).extracting(Document::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(secondPage.getContent()).extracting(Document::getId)
                .startsWith(third.getId());
        assertThat(firstPage.getTotalElements()).isGreaterThanOrEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo((int) Math.ceil(firstPage.getTotalElements() / 2.0));
    }

    @Test
    @DisplayName("creator, sector and suggested category come loaded, and a document with no suggestion is still queued")
    void fetchesWhatTheQueueShows() {
        Document suggested = persist(DocumentStatus.PENDING_APPROVAL, 1);
        Document unsuggested = persist(DocumentStatus.PENDING_APPROVAL, 2);
        Document managed = entityManager.find(Document.class, suggested.getId());
        managed.setSuggestedCategory(category);
        managed.setCategoryConfidence(new BigDecimal("0.870"));
        entityManager.flush();

        Page<Document> page = pendingPage(0, 2);
        Document withSuggestion = page.getContent().get(0);
        Document withoutSuggestion = page.getContent().get(1);

        assertThat(withSuggestion.getId()).isEqualTo(suggested.getId());
        assertThat(Hibernate.isInitialized(withSuggestion.getCreator())).isTrue();
        assertThat(Hibernate.isInitialized(withSuggestion.getCreator().getSector())).isTrue();
        assertThat(Hibernate.isInitialized(withSuggestion.getSuggestedCategory())).isTrue();
        assertThat(withSuggestion.getSuggestedCategory().getName()).isEqualTo(category.getName());
        assertThat(withSuggestion.getCategoryConfidence()).isEqualByComparingTo("0.87");

        assertThat(withoutSuggestion.getId()).isEqualTo(unsuggested.getId());
        assertThat(withoutSuggestion.getSuggestedCategory()).isNull();
    }
}
