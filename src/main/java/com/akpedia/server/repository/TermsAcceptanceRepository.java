package com.akpedia.server.repository;

import com.akpedia.server.entity.TermsAcceptance;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TermsAcceptanceRepository extends JpaRepository<TermsAcceptance, Long> {

    /** The latest acceptance of the user with this Keycloak account id, without creating their row. */
    @EntityGraph(attributePaths = "termsVersion")
    Optional<TermsAcceptance> findFirstByUserKeycloakIdOrderByAcceptedAtDescIdDesc(String keycloakId);

    /**
     * The version each of these accounts accepted last (by {@code accepted_at}, then id, as
     * {@link #findFirstByUserKeycloakIdOrderByAcceptedAtDescIdDesc}), as {@code [keycloakId, version]}
     * pairs; accounts that accepted nothing are left out. Not the "highest" version: versions are
     * labels the admin typed.
     */
    @Query("""
            SELECT u.keycloakId, v.version FROM TermsAcceptance a JOIN a.user u JOIN a.termsVersion v
            WHERE u.keycloakId IN :keycloakIds
              AND NOT EXISTS (
                  SELECT b.id FROM TermsAcceptance b
                  WHERE b.user = a.user
                    AND (b.acceptedAt > a.acceptedAt OR (b.acceptedAt = a.acceptedAt AND b.id > a.id)))""")
    List<Object[]> findLastAcceptedVersions(@Param("keycloakIds") Collection<String> keycloakIds);
}
