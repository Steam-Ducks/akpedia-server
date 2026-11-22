package com.akpedia.server.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.akpedia.server.entity.ProfileChangeRequest;
import com.akpedia.server.entity.enums.ChangeRequestStatus;
import com.akpedia.server.entity.enums.ProfileField;

import jakarta.persistence.LockModeType;

public interface ProfileChangeRequestRepository extends JpaRepository<ProfileChangeRequest, Long> {

    /** The user's requests, newest first. */
    @EntityGraph(attributePaths = {"user", "decidedBy"})
    List<ProfileChangeRequest> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    boolean existsByUserIdAndFieldAndStatus(Long userId, ProfileField field, ChangeRequestStatus status);

    @EntityGraph(attributePaths = {"user", "decidedBy"})
    Page<ProfileChangeRequest> findByStatus(ChangeRequestStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"user", "decidedBy"})
    Page<ProfileChangeRequest> findAll(Pageable pageable);

    /** The request, locked until the transaction ends, so two admins cannot decide it both. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ProfileChangeRequest r WHERE r.id = :id")
    Optional<ProfileChangeRequest> findForDecision(@Param("id") Long id);

    /** Every request of a deleted account: they hold its personal data. */
    @Modifying
    @Query("DELETE FROM ProfileChangeRequest r WHERE r.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** Unlinks a deleted admin from the requests they decided; the decisions stay. */
    @Modifying
    @Query("UPDATE ProfileChangeRequest r SET r.decidedBy = NULL WHERE r.decidedBy.id = :userId")
    int clearDecidedBy(@Param("userId") Long userId);
}
