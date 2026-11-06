package com.akpedia.server.repository;

import com.akpedia.server.entity.TermsAcceptance;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermsAcceptanceRepository extends JpaRepository<TermsAcceptance, Long> {

    /** The latest acceptance of the user with this Keycloak account id, without creating their row. */
    @EntityGraph(attributePaths = "termsVersion")
    Optional<TermsAcceptance> findFirstByUserKeycloakIdOrderByAcceptedAtDescIdDesc(String keycloakId);
}
