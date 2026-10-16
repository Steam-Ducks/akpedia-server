package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
 * The submissions query against the database: its status filter, its ordering, and that the
 * paging and the count agree. The {@code JOIN FETCH} with a {@code Pageable} and a separate
 * count query is what is really being checked -- the combination the mapped tests cannot see.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SubmissionsQueryTest {

    /** The statuses the service asks for; kept here so the query is tested on its own terms. */
    private static final List<DocumentStatus> SUBMITTED = List.of(
            DocumentStatus.PENDING_APPROVAL, DocumentStatus.APPROVED, DocumentStatus.REJECTED);

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DocumentRepository documentRepository;

    private Category category;
    private User author;
    private User somebodyElse;

    @BeforeEach
    void setUp() {
        category = entityManager.persist(TestFixtures.category());
        author = entityManager.persist(TestFixtures.user(entityManager.persist(TestFixtures.sector())));
        somebodyElse = entityManager.persist(TestFixtures.user(entityManager.persist(TestFixtures.sector())));
    }

    private Document persist(User creator, DocumentStatus status) {
        Document document = TestFixtures.document(category, creator);
        document.setStatus(status);
        return entityManager.persistAndFlush(document);
    }

    private Page<Document> submissionsOf(User creator, int page, int size) {
        entityManager.clear();
        return documentRepository.findSubmissions(creator.getId(), SUBMITTED, PageRequest.of(page, size));
    }

    @Test
    @DisplayName("the three submitted statuses are listed and nothing else is")
    void listsOnlySubmittedStatuses() {
        Document underReview = persist(author, DocumentStatus.PENDING_APPROVAL);
        Document published = persist(author, DocumentStatus.APPROVED);
        Document returned = persist(author, DocumentStatus.REJECTED);
        Document draft = persist(author, DocumentStatus.DRAFT);
        Document archived = persist(author, DocumentStatus.ARCHIVED);

        List<Long> found = submissionsOf(author, 0, 50).getContent().stream().map(Document::getId).toList();

        assertThat(found)
                .contains(underReview.getId(), published.getId(), returned.getId())
                .doesNotContain(draft.getId(), archived.getId());
    }

    @Test
    @DisplayName("another author's documents are never listed")
    void listsNobodyElsesDocuments() {
        Document mine = persist(author, DocumentStatus.APPROVED);
        Document theirs = persist(somebodyElse, DocumentStatus.APPROVED);

        List<Long> found = submissionsOf(author, 0, 50).getContent().stream().map(Document::getId).toList();

        assertThat(found).contains(mine.getId()).doesNotContain(theirs.getId());
    }

    @Test
    @DisplayName("the most recently submitted comes first")
    void mostRecentFirst() {
        Document first = persist(author, DocumentStatus.APPROVED);
        Document second = persist(author, DocumentStatus.APPROVED);
        Document third = persist(author, DocumentStatus.APPROVED);

        List<Long> found = submissionsOf(author, 0, 50).getContent().stream().map(Document::getId).toList();

        // Persisted in order, so the newest is last in; the id breaks the tie when two documents
        // land in the same instant, which is why the three come back reversed and not shuffled.
        assertThat(found).containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    @DisplayName("the page holds only its own slice, and the count covers the whole set")
    void pagingSlicesAndCounts() {
        persist(author, DocumentStatus.APPROVED);
        persist(author, DocumentStatus.PENDING_APPROVAL);
        persist(author, DocumentStatus.REJECTED);

        Page<Document> firstPage = submissionsOf(author, 0, 2);
        Page<Document> secondPage = submissionsOf(author, 1, 2);

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent()).hasSize(1);
        // The count comes from its own query: it must see all three, not just the page.
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("the category comes fetched, so rendering a row opens no further query")
    void categoryComesFetched() {
        persist(author, DocumentStatus.APPROVED);

        Document found = submissionsOf(author, 0, 1).getContent().get(0);
        entityManager.clear();

        assertThat(found.getCategory().getName()).isEqualTo(category.getName());
    }

    @Test
    @DisplayName("an author with no submissions gets an empty page, not a failure")
    void noSubmissionsIsAnEmptyPage() {
        Page<Document> found = submissionsOf(somebodyElse, 0, 10);

        assertThat(found.getContent()).isEmpty();
        assertThat(found.getTotalElements()).isZero();
    }
}
