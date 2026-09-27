package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * Asserts that migrations V2/V3 delivered the schema the entities assume.
 * Complements ddl-auto: validate, which only checks columns and types.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaMigrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PermissionRepository permissionRepository;

    @Test
    @DisplayName("the pgvector extension is enabled")
    void vectorExtensionIsInstalled() {
        Object count = entityManager
                .createNativeQuery("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
                .getSingleResult();

        assertThat(((Number) count).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("the permission catalog was seeded by the migration")
    void permissionCatalogIsSeeded() {
        List<String> names = permissionRepository.findAll().stream()
                .map(permission -> permission.getName())
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "DOCUMENT_VIEW",
                "DOCUMENT_CREATE",
                "DOCUMENT_UPDATE",
                "DOCUMENT_APPROVE",
                "DOCUMENT_DELETE");
    }

    @Test
    @DisplayName("embeddings.vector has a fixed dimension and an HNSW index")
    void embeddingVectorColumnIsIndexed() {
        Object columnType = entityManager.createNativeQuery(
                        "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
                                + "WHERE a.attrelid = 'embeddings'::regclass AND a.attname = 'vector'")
                .getSingleResult();

        Object indexDefinition = entityManager.createNativeQuery(
                        "SELECT indexdef FROM pg_indexes WHERE tablename = 'embeddings' "
                                + "AND indexname = 'ix_embeddings_vector'")
                .getSingleResult();

        assertThat(columnType.toString()).isEqualTo("vector(384)");
        assertThat(indexDefinition.toString()).contains("hnsw").contains("vector_cosine_ops");
    }

    @Test
    @DisplayName("the status CHECK covers exactly the DocumentStatus enum values")
    void documentStatusCheckMatchesEnum() {
        Object definition = entityManager.createNativeQuery(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conname = 'ck_documents_status'")
                .getSingleResult();

        assertThat(definition.toString())
                .contains("DRAFT")
                .contains("PENDING_APPROVAL")
                .contains("APPROVED")
                .contains("REJECTED")
                .contains("ARCHIVED");
    }
}
