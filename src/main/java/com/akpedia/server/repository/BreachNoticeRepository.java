package com.akpedia.server.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.akpedia.server.entity.BreachNotice;

public interface BreachNoticeRepository extends JpaRepository<BreachNotice, Long> {

    /** One page of the notices, with who sent each one. */
    @EntityGraph(attributePaths = "sentBy")
    @Query("SELECT n FROM BreachNotice n")
    Page<BreachNotice> findPage(Pageable pageable);

    /** The notice with who sent it. */
    @EntityGraph(attributePaths = "sentBy")
    Optional<BreachNotice> findWithSenderById(Long id);
}
