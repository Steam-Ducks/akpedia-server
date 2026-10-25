package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;

/**
 * The queries the review decisions rely on, against the real schema: deleting a document's file
 * without the document, locking a document to decide on it, and the outbox queries that find and
 * record the approved documents handed to the indexing worker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReviewQueriesTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentFileRepository documentFileRepository;

    private Category category;
    private User creator;

    @BeforeEach
    void setUp() {
        category = entityManager.persist(TestFixtures.category());
        creator = entityManager.persist(TestFixtures.user(entityManager.persist(TestFixtures.sector())));
    }

    private Document persist(DocumentStatus status, ProcessingStatus processing) {
        Document document = TestFixtures.document(category, creator);
        document.setStatus(status);
        document.setProcessingStatus(processing);
        return entityManager.persistAndFlush(document);
    }

    @Test
    @DisplayName("deleting a document's file removes the file row and keeps the document row")
    void deletingTheFileKeepsTheDocument() {
        Document document = persist(DocumentStatus.REJECTED, ProcessingStatus.PENDING);
        entityManager.persistAndFlush(new DocumentFile(document, "%PDF-1.4".getBytes()));

        int deleted = documentFileRepository.deleteByDocumentId(document.getId());
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(documentFileRepository.findByDocumentId(document.getId())).isEmpty();
        assertThat(documentRepository.findById(document.getId())).isPresent();
    }

    @Test
    @DisplayName("deleting the file of a document that has none deletes nothing and does not fail")
    void deletingAMissingFileIsHarmless() {
        Document document = persist(DocumentStatus.PENDING_APPROVAL, ProcessingStatus.PENDING);

        assertThat(documentFileRepository.deleteByDocumentId(document.getId())).isZero();
    }

    @Test
    @DisplayName("the document is found for review by id, under a lock the database accepts")
    void findForReviewLocksTheDocument() {
        Document document = persist(DocumentStatus.PENDING_APPROVAL, ProcessingStatus.PENDING);
        entityManager.clear();

        // Runs the SELECT ... FOR UPDATE for real: a lock mode the dialect could not express
        // would fail here, not in production.
        assertThat(documentRepository.findForReview(document.getId()))
                .get()
                .extracting(Document::getStatus)
                .isEqualTo(DocumentStatus.PENDING_APPROVAL);
        assertThat(documentRepository.findForReview(-1L)).isEmpty();
    }

    private Document approved(ProcessingStatus processing, OffsetDateTime approvedAt) {
        Document document = TestFixtures.document(category, creator);
        document.setStatus(DocumentStatus.APPROVED);
        document.setProcessingStatus(processing);
        document.setApprovedAt(approvedAt);
        return entityManager.persistAndFlush(document);
    }

    @Test
    @DisplayName("recording the send keeps the time of the first one, and does not touch updated_at")
    void markEnqueuedRecordsTheFirstSendOnly() {
        Document document = approved(ProcessingStatus.PROCESSING, OffsetDateTime.now());
        entityManager.clear();
        // As stored, not as held in memory: the column keeps microseconds, the clock may not.
        OffsetDateTime updatedBefore = documentRepository.findById(document.getId()).orElseThrow().getUpdatedAt();
        entityManager.clear();
        OffsetDateTime first = OffsetDateTime.parse("2026-10-20T14:00:00Z");

        int recorded = documentRepository.markEnqueued(document.getId(), first);
        int again = documentRepository.markEnqueued(document.getId(), first.plusMinutes(5));
        entityManager.clear();

        Document reloaded = documentRepository.findById(document.getId()).orElseThrow();
        assertThat(recorded).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(reloaded.getProcessingEnqueuedAt()).isAtSameInstantAs(first);
        // Being enqueued is not a change to the document: the recent listing orders by updated_at.
        if (updatedBefore == null) {
            assertThat(reloaded.getUpdatedAt()).isNull();
        } else {
            assertThat(reloaded.getUpdatedAt()).isAtSameInstantAs(updatedBefore);
        }
    }

    @Test
    @DisplayName("only approved documents still processing, never enqueued and past the cutoff are pending")
    void findPendingEnqueueTakesOnlyTheStrandedOnes() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(1);
        OffsetDateTime longAgo = cutoff.minusHours(1);
        Document stranded = approved(ProcessingStatus.PROCESSING, longAgo);
        Document justApproved = approved(ProcessingStatus.PROCESSING, OffsetDateTime.now());
        Document alreadyEnqueued = approved(ProcessingStatus.PROCESSING, longAgo);
        documentRepository.markEnqueued(alreadyEnqueued.getId(), longAgo);
        Document finished = approved(ProcessingStatus.COMPLETED, longAgo);
        Document pending = persist(DocumentStatus.PENDING_APPROVAL, ProcessingStatus.PENDING);
        entityManager.clear();

        List<Long> found = documentRepository.findPendingEnqueue(cutoff, PageRequest.of(0, 50));

        assertThat(found)
                .contains(stranded.getId())
                .doesNotContain(justApproved.getId(), alreadyEnqueued.getId(), finished.getId(), pending.getId());
    }

    @Test
    @DisplayName("the stranded documents come oldest approval first, one batch at a time")
    void findPendingEnqueueIsOrderedAndBounded() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(1);
        Document older = approved(ProcessingStatus.PROCESSING, cutoff.minusHours(2));
        Document newer = approved(ProcessingStatus.PROCESSING, cutoff.minusHours(1));
        entityManager.clear();

        List<Long> all = documentRepository.findPendingEnqueue(cutoff, PageRequest.of(0, 50));
        List<Long> one = documentRepository.findPendingEnqueue(cutoff, PageRequest.of(0, 1));

        assertThat(all.indexOf(older.getId())).isLessThan(all.indexOf(newer.getId()));
        assertThat(one).hasSize(1);
    }
}
