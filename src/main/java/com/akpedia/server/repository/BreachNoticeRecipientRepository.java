package com.akpedia.server.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.akpedia.server.entity.BreachNoticeRecipient;

public interface BreachNoticeRecipientRepository extends JpaRepository<BreachNoticeRecipient, Long> {

    /** A notice's recipients, with their users, in the order they were added. */
    @EntityGraph(attributePaths = "user")
    List<BreachNoticeRecipient> findByNoticeIdOrderById(Long noticeId);

    /**
     * How many recipients of each notice are in each status, as {@code [notice_id, status, count]}
     * rows; a status no recipient is in has no row.
     */
    @Query("""
            SELECT r.notice.id, r.status, COUNT(r)
            FROM BreachNoticeRecipient r
            WHERE r.notice.id IN :noticeIds
            GROUP BY r.notice.id, r.status""")
    List<Object[]> countByStatus(@Param("noticeIds") Collection<Long> noticeIds);

    /**
     * Holds back every other breach notice send until this transaction ends, so two sends can never
     * both find "nothing in progress" and go out together. A transaction-level advisory lock: it is
     * released on commit or rollback, never left behind.
     *
     * @return always 1
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
    Integer lockSending(@Param("key") long key);

    /** Whether a notice sent since then still has a recipient not tried yet: it is being sent now. */
    @Query("""
            SELECT COUNT(r) > 0 FROM BreachNoticeRecipient r
            WHERE r.status = com.akpedia.server.entity.enums.BreachNoticeRecipientStatus.PENDING
              AND r.notice.sentAt >= :since""")
    boolean existsPendingSince(@Param("since") OffsetDateTime since);

    /**
     * How many of these users already received this very notice -- same subject and text, accepted
     * by the SMTP server -- since then. What a resend of a notice that went out looks like.
     */
    @Query("""
            SELECT COUNT(DISTINCT r.user.id) FROM BreachNoticeRecipient r
            WHERE r.status = com.akpedia.server.entity.enums.BreachNoticeRecipientStatus.SENT
              AND r.notice.sentAt >= :since
              AND r.notice.subject = :subject
              AND r.notice.body = :body
              AND r.user.id IN :userIds""")
    long countAlreadySent(
            @Param("subject") String subject,
            @Param("body") String body,
            @Param("userIds") Collection<Long> userIds,
            @Param("since") OffsetDateTime since);

    /**
     * Replaces the address a deleted account's notices went to with its row's anonymized one. The
     * rows stay, as the record that the user was notified; who they were does not.
     *
     * @return how many rows changed
     */
    @Modifying
    @Query("UPDATE BreachNoticeRecipient r SET r.email = :email WHERE r.user.id = :userId")
    int replaceEmailOf(@Param("userId") Long userId, @Param("email") String email);
}
