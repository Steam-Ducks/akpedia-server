package com.akpedia.server.repository;

import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByStatus(DocumentStatus status);

    List<Document> findByProcessingStatus(ProcessingStatus processingStatus);

    List<Document> findByCategoryId(Long categoryId);

    /**
     * Approved documents in the given processing state, most recently changed first.
     *
     * <p>A document never edited counts from its creation. Category and creator come fetched,
     * since whoever lists documents shows both.
     *
     * <p>{@code APPROVED} is written into the query rather than taken as a parameter: this is a
     * public listing, and a caller able to choose which statuses it shows is exactly how a
     * document still under review reaches someone who should not see it. The same rule, in the
     * same words, guards {@link EmbeddingRepository#search}; the two have to move together.
     */
    @Query("""
            SELECT d FROM Document d
            JOIN FETCH d.category
            JOIN FETCH d.creator
            WHERE d.processingStatus = :processingStatus
              AND d.status = com.akpedia.server.entity.enums.DocumentStatus.APPROVED
            ORDER BY COALESCE(d.updatedAt, d.createdAt) DESC, d.id DESC
            """)
    List<Document> findRecent(
            @Param("processingStatus") ProcessingStatus processingStatus,
            Pageable pageable);

    /**
     * One page of an author's own submissions, most recently submitted first.
     *
     * <p>Only documents with a status the author can act on: {@code DRAFT} never left the author's
     * hands and {@code ARCHIVED} is out of circulation, and neither maps to a submission state.
     *
     * <p>Ordered by {@code createdAt}, with the id breaking ties so that two documents submitted in
     * the same instant do not swap places between pages and hide one of themselves.
     *
     * <p>The category comes fetched because every row shows it; the creator does not, since the
     * caller already knows whose submissions these are.
     *
     * <p>{@code hasCategories} and {@code categoryIds} filter by category when the caller asked
     * for one or more; {@code categoryIds} still needs a value when there is no filter, since an
     * empty {@code IN ()} is invalid -- the same convention as {@link EmbeddingRepository#search}.
     */
    @Query(value = """
            SELECT d FROM Document d
            JOIN FETCH d.category
            WHERE d.creator.id = :creatorId
              AND d.status IN :statuses
              AND (:hasCategories = false OR d.category.id IN :categoryIds)
            ORDER BY d.createdAt DESC, d.id DESC
            """,
            countQuery = """
            SELECT count(d) FROM Document d
            WHERE d.creator.id = :creatorId
              AND d.status IN :statuses
              AND (:hasCategories = false OR d.category.id IN :categoryIds)
            """)
    Page<Document> findSubmissions(
            @Param("creatorId") Long creatorId,
            @Param("statuses") Collection<DocumentStatus> statuses,
            @Param("hasCategories") boolean hasCategories,
            @Param("categoryIds") Collection<Long> categoryIds,
            Pageable pageable);

    /**
     * Documents in the given status, the oldest first, so a review queue is worked in arrival
     * order. The id breaks ties between documents created at the same instant, which keeps the
     * pages stable. Creator, the creator's sector and the suggested category come fetched, since
     * the queue shows all three; the suggestion is optional, hence the left join.
     */
    @Query(value = """
            SELECT d FROM Document d
            JOIN FETCH d.creator c
            JOIN FETCH c.sector
            JOIN FETCH d.category
            LEFT JOIN FETCH d.suggestedCategory
            WHERE d.status = :status
            ORDER BY d.createdAt ASC, d.id ASC
            """,
            countQuery = "SELECT COUNT(d) FROM Document d WHERE d.status = :status")
    Page<Document> findOldestFirstByStatus(@Param("status") DocumentStatus status, Pageable pageable);

    /**
     * The document, locked until the transaction ends, for an approver to decide on.
     *
     * <p>The lock is what makes the {@code PENDING_APPROVAL} check hold: two approvers deciding
     * at once would otherwise both see it pending, and the document would be approved twice --
     * and handed to the indexing worker twice -- or approved and rejected at the same time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :id")
    Optional<Document> findForReview(@Param("id") Long id);

    /**
     * The document, locked until the transaction ends, for the indexing worker's result to land on.
     *
     * <p>The lock is what makes a repeated call back a no-op: the worker may report the same
     * document twice (a message delivered again after a restart), and without it both calls would
     * see it unfinished and both write its embeddings.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :id")
    Optional<Document> findForIndexing(@Param("id") Long id);

    /**
     * Records that the approved document was handed to the indexing worker.
     *
     * <p>Native SQL because the column is read-only on the entity (see
     * {@link Document#getProcessingEnqueuedAt()}), and conditional so a second send -- the retry
     * racing the first one -- keeps the time of the first. Writing it this way also leaves
     * {@code updated_at} alone: being enqueued is not a change to the document.
     *
     * @return how many rows changed: 0 when it was already recorded
     */
    @Modifying
    @Query(value = """
            UPDATE documents
            SET processing_enqueued_at = :enqueuedAt
            WHERE id = :id
              AND processing_enqueued_at IS NULL
            """, nativeQuery = true)
    int markEnqueued(@Param("id") Long id, @Param("enqueuedAt") OffsetDateTime enqueuedAt);

    /**
     * Approved documents that were never handed to the indexing worker, oldest approval first.
     *
     * <p>{@code approvedBefore} leaves out the ones just approved: those are normally being sent
     * right after their commit, and sending them from here too would only duplicate them.
     */
    @Query("""
            SELECT d.id FROM Document d
            WHERE d.status = com.akpedia.server.entity.enums.DocumentStatus.APPROVED
              AND d.processingStatus = com.akpedia.server.entity.enums.ProcessingStatus.PROCESSING
              AND d.processingEnqueuedAt IS NULL
              AND d.approvedAt < :approvedBefore
            ORDER BY d.approvedAt ASC, d.id ASC
            """)
    List<Long> findPendingEnqueue(@Param("approvedBefore") OffsetDateTime approvedBefore, Pageable pageable);
}
