package com.akpedia.server.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.akpedia.server.dto.AuditKind;
import com.akpedia.server.entity.AuditLog;

import jakarta.persistence.criteria.Predicate;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByUserId(Long userId);

    /** The emails this account's entries were recorded with (its token's, request by request). */
    @Query("SELECT DISTINCT a.userEmail FROM AuditLog a WHERE a.userKeycloakId = :keycloakId AND a.userEmail IS NOT NULL")
    List<String> findEmailsOf(@Param("keycloakId") String keycloakId);

    /**
     * Strips who it was, from where and with what, from a deleted user's entries. What they did,
     * and when, stays, as does their Keycloak id.
     *
     * @return how many entries were changed
     */
    @Modifying
    @Query("""
            UPDATE AuditLog a SET a.userEmail = NULL, a.ipAddress = NULL, a.userAgent = NULL
            WHERE (a.userKeycloakId = :keycloakId OR a.user.id = :userId)
            AND (a.userEmail IS NOT NULL OR a.ipAddress IS NOT NULL OR a.userAgent IS NOT NULL)""")
    int stripPersonalData(@Param("keycloakId") String keycloakId, @Param("userId") Long userId);

    /**
     * Links a deleted user's entries that have no row yet to their anonymized row, so each shows
     * the same pseudonym ("Usuário removido #id") once the Keycloak id no longer leads to them.
     *
     * @return how many entries were linked
     */
    @Modifying
    @Query(value = """
            UPDATE audit_logs SET user_id = :userId
            WHERE user_keycloak_id = :keycloakId AND user_id IS NULL""", nativeQuery = true)
    int linkToRow(@Param("keycloakId") String keycloakId, @Param("userId") Long userId);

    /**
     * Takes a deleted user's email out of the entries others wrote about them (an admin creating,
     * enabling or changing the roles of the account): {@code target_keycloak_id} stays, as the
     * pseudonym.
     *
     * @return how many entries were changed
     */
    @Modifying
    @Query(value = """
            UPDATE audit_logs SET details = details - 'target_email'
            WHERE details ->> 'target_keycloak_id' = :keycloakId AND details -> 'target_email' IS NOT NULL""",
            nativeQuery = true)
    int stripTargetEmail(@Param("keycloakId") String keycloakId);

    /**
     * Drops the query string of the requests that searched for this text (a deleted user's email,
     * as typed or URL-encoded), such as an admin's {@code GET /admin/users?search=}. The path stays.
     *
     * @param text lower case
     * @return how many entries were changed
     */
    @Modifying
    @Query(value = """
            UPDATE audit_logs SET details = NULLIF(details - 'query', '{}'::jsonb)
            WHERE action = 'HTTP_REQUEST' AND strpos(lower(details ->> 'query'), :text) > 0""",
            nativeQuery = true)
    int stripQueriesContaining(@Param("text") String text);

    /** Loads each entry's user with it, for the name the audit screen shows. */
    @Override
    @EntityGraph(attributePaths = "user")
    Page<AuditLog> findAll(Specification<AuditLog> spec, Pageable pageable);

    /** Every action recorded other than {@code excluded}, each once. */
    @Query("SELECT DISTINCT a.action FROM AuditLog a WHERE a.action <> :excluded")
    List<String> findDistinctActionsExcept(@Param("excluded") String excluded);

    /**
     * The entries the audit screen filters for. Every argument but {@code kind} is optional.
     *
     * @param kind      named events, requests, or both
     * @param action    exact action
     * @param userEmail part of the user's email, in any case
     * @param from      created at or after
     * @param to        created at or before
     */
    static Specification<AuditLog> matching(
            AuditKind kind, String action, String userEmail, OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (kind == AuditKind.EVENT) {
                predicates.add(cb.notEqual(root.get("action"), AuditLog.HTTP_REQUEST));
            } else if (kind == AuditKind.REQUEST) {
                predicates.add(cb.equal(root.get("action"), AuditLog.HTTP_REQUEST));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action.strip()));
            }
            if (userEmail != null && !userEmail.isBlank()) {
                predicates.add(cb.like(cb.lower(root.<String>get("userEmail")),
                        "%" + escapeLike(userEmail.strip().toLowerCase(Locale.ROOT)) + "%", '\\'));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<OffsetDateTime>get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<OffsetDateTime>get("createdAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** So a {@code _} or {@code %} typed in the filter is looked for as itself. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
