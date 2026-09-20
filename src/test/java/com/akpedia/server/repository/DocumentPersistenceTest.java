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
 * Persistencia do documento e das entidades que dependem dele.
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
    @DisplayName("documento nasce com processing_status PENDING e created_at preenchido")
    void documentDefaultsAreApplied() {
        entityManager.clear();

        Document found = documentRepository.findById(document.getId()).orElseThrow();

        assertThat(found.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(found.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getApprovedAt()).isNull();
    }

    @Test
    @DisplayName("o binario fica em document_files e volta intacto")
    void fileDataRoundTrips() {
        byte[] content = "conteudo binario".getBytes(StandardCharsets.UTF_8);
        entityManager.persist(new DocumentFile(document, content));
        entityManager.flush();
        entityManager.clear();

        DocumentFile found = documentFileRepository.findByDocumentId(document.getId()).orElseThrow();

        assertThat(found.getFileData()).isEqualTo(content);
    }

    @Test
    @DisplayName("o vetor de 1536 dimensoes volta integro do pgvector")
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
    @DisplayName("chunks do mesmo documento saem ordenados pelo indice")
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
    @DisplayName("o mesmo chunk_index nao pode repetir no documento")
    void duplicatedChunkIndexIsRejected() {
        embeddingRepository.saveAndFlush(new Embedding(document, 0, "a", TestFixtures.vector(1f), "modelo", 1536));
        Embedding duplicated = new Embedding(document, 0, "b", TestFixtures.vector(2f), "modelo", 1536);

        assertThatThrownBy(() -> embeddingRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chunk_index negativo viola o CHECK do banco")
    void negativeChunkIndexIsRejected() {
        Embedding invalid = new Embedding(document, -1, "invalido", TestFixtures.vector(1f), "modelo", 1536);

        assertThatThrownBy(() -> embeddingRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("file_size zerado viola o CHECK do banco")
    void nonPositiveFileSizeIsRejected() {
        document.setFileSize(0L);

        assertThatThrownBy(() -> documentRepository.saveAndFlush(document))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("audit_logs grava details como JSONB")
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
    @DisplayName("audit_logs aceita acao sem usuario associado")
    void auditLogAllowsNullUser() {
        AuditLog log = new AuditLog(null, "SYSTEM_STARTUP", null, null);
        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        assertThat(auditLogRepository.findById(log.getId()).orElseThrow().getUser()).isNull();
    }

    @Test
    @DisplayName("busca de documentos por status e por status de processamento")
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
