package com.akpedia.server.repository;

import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     */
    @Query(value = """
            SELECT d FROM Document d
            JOIN FETCH d.category
            WHERE d.creator.id = :creatorId
              AND d.status IN :statuses
            ORDER BY d.createdAt DESC, d.id DESC
            """,
            countQuery = """
            SELECT count(d) FROM Document d
            WHERE d.creator.id = :creatorId
              AND d.status IN :statuses
            """)
    Page<Document> findSubmissions(
            @Param("creatorId") Long creatorId,
            @Param("statuses") Collection<DocumentStatus> statuses,
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
            LEFT JOIN FETCH d.suggestedCategory
            WHERE d.status = :status
            ORDER BY d.createdAt ASC, d.id ASC
            """,
            countQuery = "SELECT COUNT(d) FROM Document d WHERE d.status = :status")
    Page<Document> findOldestFirstByStatus(@Param("status") DocumentStatus status, Pageable pageable);
}
