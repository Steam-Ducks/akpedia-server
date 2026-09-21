package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Persistence of a document and of the entities that depend on it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DocumentPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentFileRepository documentFileRepository;

    @Autowired
    private EmbeddingRepository embeddingRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private Document document;

    @BeforeEach
    void setUp() {
        Sector sector = entityManager.persist(TestFixtures.sector());
        Category category = entityManager.persist(TestFixtures.category());
        User creator = entityManager.persist(TestFixtures.user(sector));
        document = entityManager.persist(TestFixtures.document(category, creator));
        entityManager.flush();
    }

    @Test
    @DisplayName("a document starts with processing_status PENDING and created_at set")
    void documentDefaultsAreApplied() {
        entityManager.clear();

        Document found = documentRepository.findById(document.getId()).orElseThrow();

        assertThat(found.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(found.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getApprovedAt()).isNull();
    }

    @Test
    @DisplayName("the binary lives in document_files and comes back intact")
    void fileDataRoundTrips() {
        byte[] content = "conteudo binario".getBytes(StandardCharsets.UTF_8);
        entityManager.persist(new DocumentFile(document, content));
        entityManager.flush();
        entityManager.clear();

        DocumentFile found = documentFileRepository.findByDocumentId(document.getId()).orElseThrow();

        assertThat(found.getFileData()).isEqualTo(content);
    }

    @Test
    @DisplayName("the 1536-dimension vector round-trips through pgvector")
    void embeddingVectorRoundTrips() {
        float[] vector = TestFixtures.vector(0.5f);
        entityManager.persist(new Embedding(document, 0, "primeiro chunk", vector, "text-embedding-3-small", 1536));
        entityManager.flush();
        entityManager.clear();

        Embedding found = embeddingRepository.findByDocumentIdOrderByChunkIndex(document.getId()).get(0);

        assertThat(found.getVector()).hasSize(Embedding.VECTOR_DIMENSIONS);
        assertThat(found.getVector()).isEqualTo(vector);
        assertThat(found.getContent()).isEqualTo("primeiro chunk");
    }

    @Test
    @DisplayName("chunks of the same document come back ordered by index")
    void embeddingsAreOrderedByChunkIndex() {
        entityManager.persist(new Embedding(document, 2, "terceiro", TestFixtures.vector(3f), "modelo", 1536));
        entityManager.persist(new Embedding(document, 0, "primeiro", TestFixtures.vector(1f), "modelo", 1536));
        entityManager.persist(new Embedding(document, 1, "segundo", TestFixtures.vector(2f), "modelo", 1536));
        entityManager.flush();
        entityManager.clear();

        assertThat(embeddingRepository.findByDocumentIdOrderByChunkIndex(document.getId()))
                .extracting(Embedding::getContent)
                .containsExactly("primeiro", "segundo", "terceiro");
    }

    @Test
    @DisplayName("the same chunk_index cannot repeat within a document")
    void duplicatedChunkIndexIsRejected() {
        embeddingRepository.saveAndFlush(new Embedding(document, 0, "a", TestFixtures.vector(1f), "modelo", 1536));
        Embedding duplicated = new Embedding(document, 0, "b", TestFixtures.vector(2f), "modelo", 1536);

        assertThatThrownBy(() -> embeddingRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a negative chunk_index violates the database CHECK")
    void negativeChunkIndexIsRejected() {
        Embedding invalid = new Embedding(document, -1, "invalido", TestFixtures.vector(1f), "modelo", 1536);

        assertThatThrownBy(() -> embeddingRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a zero file_size violates the database CHECK")
    void nonPositiveFileSizeIsRejected() {
        document.setFileSize(0L);

        assertThatThrownBy(() -> documentRepository.saveAndFlush(document))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("audit_logs stores details as JSONB")
    void auditLogStoresJsonDetails() {
        AuditLog log = new AuditLog(document.getCreator(), "DOCUMENT_CREATED", "Document", document.getId());
        log.setDetails("{\"origem\":\"teste\",\"campos\":[\"name\"]}");
        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        AuditLog found = auditLogRepository.findById(log.getId()).orElseThrow();

        assertThat(found.getDetails()).contains("\"origem\"").contains("teste");
        assertThat(found.getAction()).isEqualTo("DOCUMENT_CREATED");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("audit_logs accepts an action with no associated user")
    void auditLogAllowsNullUser() {
        AuditLog log = new AuditLog(null, "SYSTEM_STARTUP", null, null);
        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        assertThat(auditLogRepository.findById(log.getId()).orElseThrow().getUser()).isNull();
    }

    @Test
    @DisplayName("finds documents by status and by processing status")
    void findsByStatusAndProcessingStatus() {
        entityManager.flush();
        entityManager.clear();

        assertThat(documentRepository.findByStatus(DocumentStatus.DRAFT))
                .extracting(Document::getId)
                .contains(document.getId());
        assertThat(documentRepository.findByProcessingStatus(ProcessingStatus.PENDING))
                .extracting(Document::getId)
                .contains(document.getId());
    }
}
