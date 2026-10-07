package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Asserts that migrations V2 to V5 delivered the schema and seeds the entities assume.
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

    @Test
    @DisplayName("documents has the review columns with the expected types")
    void documentReviewColumnsExist() {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT a.attname || ' ' || format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
                                + "WHERE a.attrelid = 'documents'::regclass AND a.attname IN ("
                                + "'review_comment', 'reviewed_at', 'suggested_category_id', 'category_confidence')")
                .getResultList();

        assertThat(rows).extracting(Object::toString).containsExactlyInAnyOrder(
                "review_comment text",
                "reviewed_at timestamp with time zone",
                "suggested_category_id bigint",
                "category_confidence numeric(4,3)");
    }

    @Test
    @DisplayName("suggested_category_id references categories with ON DELETE SET NULL")
    void suggestedCategoryForeignKeySetsNullOnDelete() {
        Object definition = entityManager.createNativeQuery(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conname = 'fk_documents_suggested_category_id'")
                .getSingleResult();

        assertThat(definition.toString())
                .contains("REFERENCES categories(id)")
                .contains("ON DELETE SET NULL");
    }

    @Test
    @DisplayName("category_confidence only accepts values between 0 and 1")
    void categoryConfidenceIsBoundedByCheck() {
        Object outOfRange = entityManager.createNativeQuery(
                        "SELECT count(*) FROM (VALUES (-0.001), (1.001)) AS v(category_confidence) "
                                + "WHERE " + categoryConfidenceCheck())
                .getSingleResult();
        Object inRange = entityManager.createNativeQuery(
                        "SELECT count(*) FROM (VALUES (0), (0.5), (1)) AS v(category_confidence) "
                                + "WHERE " + categoryConfidenceCheck())
                .getSingleResult();

        assertThat(((Number) outOfRange).intValue()).isZero();
        assertThat(((Number) inRange).intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("the category catalog was seeded by the migration")
    void categoryCatalogIsSeeded() {
        List<?> names = entityManager
                .createNativeQuery("SELECT name FROM categories")
                .getResultList();

        // Unlike permissions, categories are also created at runtime, so the seed is a subset.
        assertThat(names).extracting(Object::toString)
                .contains("Tecnico", "Regulatorio", "Juridico", "Qualidade");
    }

    @Test
    @DisplayName("the default user was seeded and belongs to a sector")
    void defaultUserIsSeededWithASector() {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT u.name || '|' || u.is_active || '|' || s.name FROM users u "
                                + "JOIN sectors s ON s.id = u.sector_id "
                                + "WHERE u.email = 'aprovador@akpedia.local'")
                .getResultList();

        // The JOIN is the point: it fails to produce a row unless sector_id really resolves.
        assertThat(rows).extracting(Object::toString)
                .containsExactly("Aprovador Padrao|true|Engenharia");
    }

    @Test
    @DisplayName("the default user has a password_hash, which the schema requires")
    void defaultUserHasAPlaceholderPasswordHash() {
        Object hash = entityManager.createNativeQuery(
                        "SELECT password_hash FROM users WHERE email = 'aprovador@akpedia.local'")
                .getSingleResult();

        assertThat(hash.toString()).isNotBlank();
    }

    @Test
    @DisplayName("re-running the seed migration does not duplicate or replace the default user")
    void defaultUserSeedIsIdempotent() {
        // Flyway applies V5 once, so the ON CONFLICT clauses are exercised here by replaying the
        // real migration file rather than a copy of its SQL: a copy would keep passing while the
        // migration drifted away from it. @DataJpaTest rolls back, so nothing survives this test.
        Object idBefore = defaultUserId();

        runScript("db/migration/V5__seed_default_approver.sql");

        Object users = entityManager.createNativeQuery(
                        "SELECT count(*) FROM users WHERE email = 'aprovador@akpedia.local'")
                .getSingleResult();
        Object sectors = entityManager.createNativeQuery(
                        "SELECT count(*) FROM sectors WHERE name = 'Engenharia'")
                .getSingleResult();

        assertThat(((Number) users).intValue()).isEqualTo(1);
        assertThat(((Number) sectors).intValue()).isEqualTo(1);
        // Same id: the row was left alone, not deleted and inserted again.
        assertThat(defaultUserId()).isEqualTo(idBefore);
    }

    private Object defaultUserId() {
        return entityManager.createNativeQuery(
                        "SELECT id FROM users WHERE email = 'aprovador@akpedia.local'")
                .getSingleResult();
    }

    /** Runs a migration file on the test's own connection, so the test transaction rolls it back. */
    private void runScript(String classpathLocation) {
        entityManager.unwrap(Session.class).doWork(connection ->
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new ClassPathResource(classpathLocation), StandardCharsets.UTF_8)));
    }

    /** The CHECK expression as the database stores it, evaluated instead of string-matched. */
    private String categoryConfidenceCheck() {
        String definition = entityManager.createNativeQuery(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conname = 'ck_documents_category_confidence'")
                .getSingleResult()
                .toString();

        return definition.substring("CHECK ".length());
    }
}
