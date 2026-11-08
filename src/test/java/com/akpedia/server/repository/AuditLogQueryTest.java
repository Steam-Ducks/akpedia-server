package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.akpedia.server.dto.AuditKind;
import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.entity.User;

/**
 * The audit screen's filters against the real schema. Every entry here belongs to a user with a
 * unique email, and every query filters on part of it, so whatever the dev database already holds
 * stays out of the results.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditLogQueryTest {

    private static final OffsetDateTime MONDAY = OffsetDateTime.parse("2026-11-02T12:00:00Z");
    private static final PageRequest NEWEST_FIRST =
            PageRequest.of(0, 50, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AuditLogRepository logs;

    private String marker;
    private User user;

    @BeforeEach
    void setUp() {
        marker = TestFixtures.unique("audit").toLowerCase();
        user = entityManager.persist(TestFixtures.user(entityManager.persist(TestFixtures.sector())));
    }

    /** An entry by "Ana.{marker}@akpedia.test", or by "outro.{marker}@..." when {@code ana} is false. */
    private AuditLog entry(String action, boolean ana, OffsetDateTime at) {
        AuditLog log = new AuditLog(ana ? user : null, action, null, null);
        log.setUserEmail((ana ? "Ana." : "outro.") + marker + "@akpedia.test");
        entityManager.persistAndFlush(log);
        // created_at is set by the database on insert; moved here to test the date filters.
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE audit_logs SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, at)
                .setParameter(2, log.getId())
                .executeUpdate();
        return log;
    }

    private List<Long> ids(AuditKind kind, String action, String userEmail, OffsetDateTime from, OffsetDateTime to) {
        entityManager.clear();
        Page<AuditLog> page = logs.findAll(AuditLogRepository.matching(kind, action, userEmail, from, to), NEWEST_FIRST);
        return page.getContent().stream().map(AuditLog::getId).toList();
    }

    @Test
    @DisplayName("EVENT leaves the requests out, REQUEST keeps only them, ALL keeps both, newest first")
    void kindSplitsEventsFromRequests() {
        AuditLog event = entry("DOCUMENT_UPLOADED", true, MONDAY);
        AuditLog request = entry(AuditLog.HTTP_REQUEST, true, MONDAY.plusHours(1));

        assertThat(ids(AuditKind.EVENT, null, marker, null, null)).containsExactly(event.getId());
        assertThat(ids(AuditKind.REQUEST, null, marker, null, null)).containsExactly(request.getId());
        assertThat(ids(AuditKind.ALL, null, marker, null, null)).containsExactly(request.getId(), event.getId());
    }

    @Test
    @DisplayName("action matches exactly")
    void actionFilter() {
        AuditLog accepted = entry("TERMS_ACCEPTED", true, MONDAY);
        entry("TERMS_DECLINED", true, MONDAY);

        assertThat(ids(AuditKind.ALL, "TERMS_ACCEPTED", marker, null, null)).containsExactly(accepted.getId());
    }

    @Test
    @DisplayName("user matches part of the email in any case, with % and _ taken literally")
    void userFilter() {
        AuditLog ana = entry("TERMS_ACCEPTED", true, MONDAY);
        entry("TERMS_ACCEPTED", false, MONDAY);

        assertThat(ids(AuditKind.ALL, null, "ANA." + marker.toUpperCase(), null, null)).containsExactly(ana.getId());
        assertThat(ids(AuditKind.ALL, null, "%" + marker, null, null)).isEmpty();
    }

    @Test
    @DisplayName("from and to are inclusive bounds on when the entry was made")
    void dateFilter() {
        AuditLog before = entry("TERMS_ACCEPTED", true, MONDAY.minusDays(1));
        AuditLog onMonday = entry("TERMS_ACCEPTED", true, MONDAY);
        AuditLog after = entry("TERMS_ACCEPTED", true, MONDAY.plusDays(1));

        assertThat(ids(AuditKind.ALL, null, marker, MONDAY, MONDAY)).containsExactly(onMonday.getId());
        assertThat(ids(AuditKind.ALL, null, marker, MONDAY, null)).containsExactly(after.getId(), onMonday.getId());
        assertThat(ids(AuditKind.ALL, null, marker, null, MONDAY)).containsExactly(onMonday.getId(), before.getId());
    }

    @Test
    @DisplayName("each entry comes with its user, for the name on screen")
    void userIsLoaded() {
        entry("TERMS_ACCEPTED", true, MONDAY);
        entityManager.clear();

        AuditLog found = logs.findAll(AuditLogRepository.matching(AuditKind.ALL, null, marker, null, null), NEWEST_FIRST)
                .getContent().get(0);

        assertThat(found.getUser().getName()).isEqualTo("Usuario de Teste");
    }

    @Test
    @DisplayName("the action list holds each event once, and never HTTP_REQUEST")
    void distinctEventActions() {
        String action = "ZZ_" + marker.toUpperCase();
        entry(action, true, MONDAY);
        entry(action, false, MONDAY);
        entry(AuditLog.HTTP_REQUEST, true, MONDAY);

        List<String> actions = logs.findDistinctActionsExcept(AuditLog.HTTP_REQUEST);

        assertThat(actions).containsOnlyOnce(action).doesNotContain(AuditLog.HTTP_REQUEST);
    }
}
