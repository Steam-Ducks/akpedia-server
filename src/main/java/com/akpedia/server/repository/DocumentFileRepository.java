package com.akpedia.server.repository;

import com.akpedia.server.entity.DocumentFile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, Long> {

    Optional<DocumentFile> findByDocumentId(Long documentId);
}
