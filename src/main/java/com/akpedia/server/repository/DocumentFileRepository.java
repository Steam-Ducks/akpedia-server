package com.akpedia.server.repository;

import com.akpedia.server.entity.DocumentFile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, Long> {

    Optional<DocumentFile> findByDocumentId(Long documentId);

    /**
     * Deletes the stored file of a document, leaving the document itself.
     *
     * <p>A bulk delete rather than a derived {@code deleteBy...}: that one loads the entity first,
     * and with it the whole binary, just to remove it.
     *
     * @return how many rows were deleted: 0 or 1
     */
    @Modifying
    @Query("DELETE FROM DocumentFile f WHERE f.document.id = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}
