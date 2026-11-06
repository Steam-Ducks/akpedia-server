package com.akpedia.server.repository;

import com.akpedia.server.entity.TermsVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TermsVersionRepository extends JpaRepository<TermsVersion, Long> {

    /** The current terms: the highest version published. */
    @EntityGraph(attributePaths = "publishedBy")
    Optional<TermsVersion> findFirstByOrderByVersionDesc();

    @EntityGraph(attributePaths = "publishedBy")
    List<TermsVersion> findAllByOrderByVersionDesc();

    /**
     * Holds back other publishes until this transaction ends, so two admins publishing at once
     * get versions n and n + 1 instead of both taking n. Reads are not blocked.
     */
    @Modifying
    @Query(value = "LOCK TABLE terms_versions IN EXCLUSIVE MODE", nativeQuery = true)
    void lockForPublishing();
}
