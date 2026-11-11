package com.akpedia.server.repository;

import com.akpedia.server.entity.TermsVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TermsVersionRepository extends JpaRepository<TermsVersion, Long> {

    /**
     * The current terms: the newest published, by {@code published_at} and then id. Never by
     * {@code version}, which is a label the admin typed ("10" sorts before "9").
     */
    @EntityGraph(attributePaths = "publishedBy")
    Optional<TermsVersion> findFirstByOrderByPublishedAtDescIdDesc();

    /** Every published version, newest first, as {@link #findFirstByOrderByPublishedAtDescIdDesc} orders them. */
    @EntityGraph(attributePaths = "publishedBy")
    List<TermsVersion> findAllByOrderByPublishedAtDescIdDesc();

    /** The version with this name, in any case; versions are unique that way. */
    @Query("SELECT v FROM TermsVersion v WHERE lower(v.version) = lower(:version)")
    Optional<TermsVersion> findByVersionIgnoreCase(@Param("version") String version);

    /** Whether a version with this name, in any case, was published before. */
    @Query("SELECT COUNT(v) > 0 FROM TermsVersion v WHERE lower(v.version) = lower(:version)")
    boolean existsByVersionIgnoreCase(@Param("version") String version);

    /**
     * Holds back other publishes until this transaction ends, so two admins publishing the same
     * version at once are told it exists instead of racing to the unique index, and the newest
     * published stays the last one saved. Reads are not blocked.
     */
    @Modifying
    @Query(value = "LOCK TABLE terms_versions IN EXCLUSIVE MODE", nativeQuery = true)
    void lockForPublishing();
}
