package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.akpedia.server.entity.BreachNotice;
import com.akpedia.server.entity.BreachNoticeRecipient;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.BreachNoticeRecipientStatus;

/** The breach notice tables (V17) and the queries written by hand against them. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BreachNoticeQueriesTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BreachNoticeRepository notices;

    @Autowired
    private BreachNoticeRecipientRepository recipients;

    @Autowired
    private UserRepository users;

    private User admin;
    private User ana;
    private User bruno;

    @BeforeEach
    void setUp() {
        Sector sector = entityManager.persist(TestFixtures.sector());
        admin = entityManager.persist(TestFixtures.user(sector));
        ana = entityManager.persist(TestFixtures.user(sector));
        bruno = entityManager.persist(TestFixtures.user(sector));
    }

    private BreachNotice sentTo(User... to) {
        BreachNotice notice = entityManager.persist(new BreachNotice(admin, "Assunto", "Texto", true));
        for (User user : to) {
            entityManager.persist(new BreachNoticeRecipient(notice, user, user.getEmail()));
        }
        return notice;
    }

    @Test
    @DisplayName("counts each notice's recipients by status")
    void countsByStatus() {
        BreachNotice notice = sentTo(ana, bruno);
        List<BreachNoticeRecipient> rows = recipients.findByNoticeIdOrderById(notice.getId());
        rows.get(0).markSent();
        rows.get(1).markFailed("REJECTED: 550");
        entityManager.flush();
        entityManager.clear();

        Map<BreachNoticeRecipientStatus, Long> counts = recipients.countByStatus(List.of(notice.getId())).stream()
                .collect(Collectors.toMap(row -> (BreachNoticeRecipientStatus) row[1], row -> ((Number) row[2]).longValue()));

        assertThat(counts).containsOnly(
                Map.entry(BreachNoticeRecipientStatus.SENT, 1L), Map.entry(BreachNoticeRecipientStatus.FAILED, 1L));
    }

    @Test
    @DisplayName("a recipient starts PENDING, and a long error is cut to the column")
    void recipientStartsPendingAndErrorIsCut() {
        BreachNotice notice = sentTo(ana);
        entityManager.flush();
        entityManager.clear();
        BreachNoticeRecipient row = recipients.findByNoticeIdOrderById(notice.getId()).get(0);
        assertThat(row.getStatus()).isEqualTo(BreachNoticeRecipientStatus.PENDING);

        row.markFailed("x".repeat(5000));
        entityManager.flush();

        assertThat(row.getError()).hasSize(BreachNoticeRecipient.MAX_ERROR);
    }

    @Test
    @DisplayName("replacing a user's address touches only that user's rows")
    void replaceEmailOfTouchesOnlyThatUser() {
        BreachNotice notice = sentTo(ana, bruno);
        entityManager.flush();

        int changed = recipients.replaceEmailOf(ana.getId(), "removido@removido.invalid");
        entityManager.clear();

        assertThat(changed).isEqualTo(1);
        assertThat(recipients.findByNoticeIdOrderById(notice.getId()))
                .extracting(BreachNoticeRecipient::getEmail)
                .containsExactly("removido@removido.invalid", bruno.getEmail());
    }

    @Test
    @DisplayName("the notice comes back with who sent it")
    void noticeWithSender() {
        BreachNotice notice = sentTo(ana);
        entityManager.flush();
        entityManager.clear();

        BreachNotice found = notices.findWithSenderById(notice.getId()).orElseThrow();

        assertThat(found.getSentBy().getName()).isEqualTo(admin.getName());
        assertThat(found.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("the send lock can be taken, and taken again in the same transaction")
    void sendLockIsReentrantInTheTransaction() {
        assertThat(recipients.lockSending(0x6272656163680001L)).isEqualTo(1);
        assertThat(recipients.lockSending(0x6272656163680001L)).isEqualTo(1);
    }

    @Test
    @DisplayName("a notice with a recipient still PENDING is in progress; once all are tried, it is not")
    void pendingMeansInProgress() {
        java.time.OffsetDateTime since = java.time.OffsetDateTime.now().minusMinutes(30);
        BreachNotice notice = sentTo(ana);
        entityManager.flush();
        // Other tests' rows may be in the database already; this one's is what is looked at.
        assertThat(recipients.existsPendingSince(since)).isTrue();

        recipients.findByNoticeIdOrderById(notice.getId()).forEach(BreachNoticeRecipient::markSent);
        entityManager.flush();

        assertThat(recipients.findByNoticeIdOrderById(notice.getId()))
                .allMatch(row -> row.getStatus() == BreachNoticeRecipientStatus.SENT);
        assertThat(recipients.existsPendingSince(java.time.OffsetDateTime.now().plusMinutes(1))).isFalse();
    }

    @Test
    @DisplayName("already sent counts only the users the same subject and text reached")
    void alreadySentCountsOnlyWhoItReached() {
        BreachNotice notice = sentTo(ana, bruno);
        List<BreachNoticeRecipient> rows = recipients.findByNoticeIdOrderById(notice.getId());
        rows.get(0).markSent();
        rows.get(1).markFailed("REJECTED: 550");
        entityManager.flush();
        java.time.OffsetDateTime since = java.time.OffsetDateTime.now().minusMinutes(30);
        List<Long> both = List.of(ana.getId(), bruno.getId());

        assertThat(recipients.countAlreadySent("Assunto", "Texto", both, since)).isEqualTo(1);
        assertThat(recipients.countAlreadySent("Assunto", "Texto", List.of(bruno.getId()), since)).isZero();
        assertThat(recipients.countAlreadySent("Outro assunto", "Texto", both, since)).isZero();
        assertThat(recipients.countAlreadySent("Assunto", "Texto", both, java.time.OffsetDateTime.now().plusMinutes(1)))
                .isZero();
    }

    @Test
    @DisplayName("users who can be notified: linked to Keycloak and not deleted")
    void notifiableUsers() {
        ana.setKeycloakId(java.util.UUID.randomUUID().toString());
        bruno.setKeycloakId(java.util.UUID.randomUUID().toString());
        bruno.anonymize(java.time.OffsetDateTime.now());
        entityManager.flush();

        List<Long> ids = users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById().stream().map(User::getId).toList();

        assertThat(ids).contains(ana.getId()).doesNotContain(bruno.getId(), admin.getId());
    }
}
