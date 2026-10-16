package com.akpedia.server.repository;

import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import java.util.List;
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
}
