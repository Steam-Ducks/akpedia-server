package com.akpedia.server.repository;

import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
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
     * Documents in the given processing state and not in the excluded status, most recently
     * changed first. A document never edited counts from its creation. Category and creator come
     * fetched, since whoever lists documents shows both.
     */
    @Query("""
            SELECT d FROM Document d
            JOIN FETCH d.category
            JOIN FETCH d.creator
            WHERE d.processingStatus = :processingStatus
              AND d.status <> :excludedStatus
            ORDER BY COALESCE(d.updatedAt, d.createdAt) DESC, d.id DESC
            """)
    List<Document> findRecent(
            @Param("processingStatus") ProcessingStatus processingStatus,
            @Param("excludedStatus") DocumentStatus excludedStatus,
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
