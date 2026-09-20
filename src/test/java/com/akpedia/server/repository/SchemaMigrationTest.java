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
 * Garante que a migration V2 entregou o schema que as entidades assumem.
 * Complementa o ddl-auto: validate, que so verifica colunas e tipos.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaMigrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PermissionRepository permissionRepository;

    @Test
    @DisplayName("a extensao pgvector esta habilitada")
    void vectorExtensionIsInstalled() {
        Object count = entityManager
                .createNativeQuery("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
                .getSingleResult();

        assertThat(((Number) count).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("o catalogo de permissoes foi populado pela migration")
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
    @DisplayName("embeddings.vector tem dimensao fixa e indice HNSW")
    void embeddingVectorColumnIsIndexed() {
        Object columnType = entityManager.createNativeQuery(
                        "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
                                + "WHERE a.attrelid = 'embeddings'::regclass AND a.attname = 'vector'")
                .getSingleResult();

        Object indexDefinition = entityManager.createNativeQuery(
                        "SELECT indexdef FROM pg_indexes WHERE tablename = 'embeddings' "
                                + "AND indexname = 'ix_embeddings_vector'")
                .getSingleResult();

        assertThat(columnType.toString()).isEqualTo("vector(1536)");
        assertThat(indexDefinition.toString()).contains("hnsw").contains("vector_cosine_ops");
    }

    @Test
    @DisplayName("o CHECK de status cobre exatamente os valores do enum DocumentStatus")
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
