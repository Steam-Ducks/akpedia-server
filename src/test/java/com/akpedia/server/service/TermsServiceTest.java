package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsAcceptanceEntry;
import com.akpedia.server.dto.TermsStatusResponse;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.entity.TermsVersion;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.exception.TermsChangedException;
import com.akpedia.server.repository.AuditLogRepository;
import com.akpedia.server.repository.OptionalPermissionChoiceRepository;
import com.akpedia.server.repository.TermsAcceptanceRepository;
import com.akpedia.server.repository.TermsVersionRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TermsService.class, AuditService.class, AccountDeletionLog.class, OptionalPermissionService.class})
class TermsServiceTest {

    @Autowired
    private TermsService terms;

    @Autowired
    private TermsVersionRepository versions;

    @Autowired
    private TermsAcceptanceRepository acceptances;

    @Autowired
    private OptionalPermissionChoiceRepository optionalChoices;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private TestEntityManager entityManager;

    private User admin;
    private User ana;

    @BeforeEach
    void setUp() {
        // Starts from no terms at all, whatever the dev database holds; the test's rollback puts it back.
        optionalChoices.deleteAllInBatch();
        acceptances.deleteAllInBatch();
        versions.deleteAllInBatch();

        Sector sector = entityManager.persist(new Sector("Setor-" + UUID.randomUUID(), null));
        admin = persistUser("Admin Akpedia", sector);
        ana = persistUser("Ana Souza", sector);
    }

    private User persistUser(String name, Sector sector) {
        User user = new User(name, UUID.randomUUID() + "@akpedia.test", sector);
        user.setKeycloakId(UUID.randomUUID().toString());
        return entityManager.persist(user);
    }

    private TermsVersionResponse publish() {
        return publish("Texto");
    }

    private TermsVersionResponse publish(String content) {
        return terms.publish(admin.getId(), new PublishTermsRequest("Termos de uso", content));
    }

    /** A version named freely, as published before the numbering. */
    private TermsVersion legacy(String version) {
        return versions.saveAndFlush(new TermsVersion(version, "Termos de uso", "Texto " + version, admin));
    }

    @Test
    @DisplayName("with no terms published, nobody is asked to accept anything")
    void noTermsNoGate() {
        TermsStatusResponse status = terms.status(ana.getKeycloakId());

        assertThat(status.current()).isNull();
        assertThat(status.accepted()).isNull();
        assertThat(terms.mustAccept(ana.getKeycloakId())).isFalse();
        assertThat(terms.mustAccept("never-logged-in")).isFalse();
    }

    @Test
    @DisplayName("each publish is the next X.0, trimmed, with who published")
    void publishNumbersVersions() {
        TermsVersionResponse first = publish("  Texto 1  ");

        assertThat(first.version()).isEqualTo("1.0");
        assertThat(first.content()).isEqualTo("Texto 1");
        assertThat(first.publishedBy()).isEqualTo("Admin Akpedia");
        assertThat(first.publishedAt()).isNotNull();
        assertThat(publish().version()).isEqualTo("2.0");
        assertThat(publish().version()).isEqualTo("3.0");
        assertThat(terms.history()).extracting(TermsVersionResponse::version).containsExactly("3.0", "2.0", "1.0");
    }

    @Test
    @DisplayName("versions are counted as numbers, so 10.0 comes after 9.0, and an old X.Y counts by its X")
    void versionsCountAsNumbers() {
        legacy("9.0");
        assertThat(publish().version()).isEqualTo("10.0");

        assertThat(TermsService.nextVersion(List.of("2.0", "10.0", "9.0"))).isEqualTo("11.0");
        assertThat(TermsService.nextVersion(List.of("1.0", "1.3"))).isEqualTo("2.0");
        assertThat(TermsService.nextVersion(List.of())).isEqualTo("1.0");
    }

    @Test
    @DisplayName("versions named before the numbering are skipped when counting, but the newest is still current")
    void legacyLabelsAreSkipped() {
        legacy("4.0");
        legacy("2026.1-rc_2");
        legacy("1.");
        legacy("OI");

        assertThat(terms.status(ana.getKeycloakId()).current().version()).isEqualTo("OI");
        assertThat(publish().version()).isEqualTo("5.0");
        assertThat(terms.status(ana.getKeycloakId()).current().version()).isEqualTo("5.0");
    }

    @Test
    @DisplayName("the current version is the newest published, even when a legacy label sorts higher")
    void currentIsNewestPublished() {
        legacy("10");
        legacy("9");

        assertThat(terms.status(ana.getKeycloakId()).current().version()).isEqualTo("9");
        assertThat(terms.history()).extracting(TermsVersionResponse::version).containsExactly("9", "10");
        assertThatThrownBy(() -> terms.accept(ana.getId(), "10")).isInstanceOf(TermsChangedException.class);
        assertThat(terms.accept(ana.getId(), "9").version()).isEqualTo("9");
        assertThat(terms.mustAccept(ana.getKeycloakId())).isFalse();
    }

    @Test
    @DisplayName("the database refuses a version that differs only in case, too")
    void databaseRefusesCaseVariant() {
        versions.saveAndFlush(new TermsVersion("rc1", "Termos", "Texto", admin));

        assertThatThrownBy(() -> versions.saveAndFlush(new TermsVersion("RC1", "Termos", "Texto", admin)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("once terms are published, everyone must accept them, the publisher included")
    void publishedTermsMustBeAccepted() {
        publish();

        assertThat(terms.mustAccept(ana.getKeycloakId())).isTrue();
        assertThat(terms.mustAccept(admin.getKeycloakId())).isTrue();
        // An account without a row yet has accepted nothing either.
        assertThat(terms.mustAccept("never-logged-in")).isTrue();
    }

    @Test
    @DisplayName("accepting the current version lets the user through, until a new one is published")
    void acceptingCurrentVersion() {
        publish();

        var accepted = terms.accept(ana.getId(), "1.0");

        assertThat(accepted.version()).isEqualTo("1.0");
        assertThat(accepted.acceptedAt()).isNotNull();
        assertThat(terms.mustAccept(ana.getKeycloakId())).isFalse();
        assertThat(terms.status(ana.getKeycloakId()).accepted().version()).isEqualTo("1.0");

        publish();

        assertThat(terms.mustAccept(ana.getKeycloakId())).isTrue();
        assertThat(terms.status(ana.getKeycloakId()).accepted().version()).isEqualTo("1.0");
        assertThat(terms.status(ana.getKeycloakId()).current().version()).isEqualTo("2.0");
    }

    @Test
    @DisplayName("accepting a version that is no longer current is refused")
    void acceptingOldVersionIsRefused() {
        publish();
        publish();

        assertThatThrownBy(() -> terms.accept(ana.getId(), "1.0"))
                .isInstanceOf(TermsChangedException.class)
                .hasMessageContaining("2.0");
        assertThat(terms.mustAccept(ana.getKeycloakId())).isTrue();
    }

    @Test
    @DisplayName("accepting when nothing was published is refused")
    void acceptingWithoutTermsIsRefused() {
        assertThatThrownBy(() -> terms.accept(ana.getId(), "1.0")).isInstanceOf(TermsChangedException.class);
    }

    @Test
    @DisplayName("every version accepted is kept, and accepting one again answers the first acceptance")
    void acceptancesAreAppendOnly() {
        publish();
        var first = terms.accept(ana.getId(), "1.0");
        var again = terms.accept(ana.getId(), "1.0");
        publish();
        terms.accept(ana.getId(), "2.0");

        assertThat(again).isEqualTo(first);
        assertThat(acceptances.count()).isEqualTo(2);
        assertThat(terms.status(ana.getKeycloakId()).accepted().version()).isEqualTo("2.0");
    }

    @Test
    @DisplayName("the admin's acceptances list is newest first, filtered by version and by the user's name or e-mail")
    void acceptancesList() {
        publish();
        terms.accept(ana.getId(), "1.0");
        terms.accept(admin.getId(), "1.0");
        publish();
        terms.accept(ana.getId(), "2.0");

        PageResponse<TermsAcceptanceEntry> all = terms.acceptances(null, null, 0, 20);
        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(all.content().get(0).version()).isEqualTo("2.0");
        assertThat(all.content().get(0).userName()).isEqualTo("Ana Souza");

        assertThat(terms.acceptances(" 1.0 ", "", 0, 20).content()).extracting(TermsAcceptanceEntry::version)
                .containsOnly("1.0").hasSize(2);
        assertThat(terms.acceptances("1.0", "ana s", 0, 20).content()).extracting(TermsAcceptanceEntry::userName)
                .containsExactly("Ana Souza");
        assertThat(terms.acceptances(null, ana.getEmail().substring(0, 8).toUpperCase(), 0, 20).totalElements())
                .isEqualTo(2);
        assertThat(terms.acceptances(null, null, 1, 2).content()).hasSize(1);
        assertThatThrownBy(() -> terms.acceptances(null, null, 0, 101)).isInstanceOf(InvalidPageRequestException.class);
    }

    @Test
    @DisplayName("the admin's users screen gets the version each user accepted last, not the highest")
    void lastAcceptedVersionsAreTheLatest() {
        legacy("9");
        terms.accept(ana.getId(), "9");
        terms.accept(admin.getId(), "9");
        legacy("10");
        terms.accept(ana.getId(), "10");
        entityManager.flush();

        List<Object[]> rows = acceptances.findLastAcceptedVersions(
                List.of(ana.getKeycloakId(), admin.getKeycloakId(), "never-logged-in"));

        assertThat(rows).extracting(row -> row[0] + "=" + row[1])
                .containsExactlyInAnyOrder(ana.getKeycloakId() + "=10", admin.getKeycloakId() + "=9");
    }

    private List<AuditLog> eventsOf(Long userId) {
        entityManager.flush();
        return auditLogs.findByUserId(userId);
    }

    @Test
    @DisplayName("publishing and accepting are recorded in the audit trail, with the version as text")
    void publishAndAcceptAreAudited() {
        TermsVersionResponse published = publish();
        terms.accept(ana.getId(), "1.0");

        assertThat(eventsOf(admin.getId())).singleElement().satisfies(event -> {
            assertThat(event.getAction()).isEqualTo("TERMS_PUBLISHED");
            assertThat(event.getEntityType()).isEqualTo("TERMS_VERSION");
            assertThat(event.getDetails()).containsPattern("\"version\":\\s?\"1\\.0\"").contains("Termos de uso");
        });
        assertThat(eventsOf(ana.getId())).singleElement().satisfies(event -> {
            assertThat(event.getAction()).isEqualTo("TERMS_ACCEPTED");
            assertThat(event.getEntityId())
                    .isEqualTo(versions.findByVersionIgnoreCase(published.version()).orElseThrow().getId());
            assertThat(event.getDetails()).containsPattern("\"version\":\\s?\"1\\.0\"");
        });
    }

    @Test
    @DisplayName("a refused acceptance leaves no event behind")
    void refusedActionsAreNotAudited() {
        assertThatThrownBy(() -> terms.accept(ana.getId(), "1.0")).isInstanceOf(TermsChangedException.class);

        assertThat(eventsOf(ana.getId())).isEmpty();
        assertThat(eventsOf(admin.getId())).isEmpty();
    }

    @Test
    @DisplayName("declining is recorded against the version shown, or none if it does not exist")
    void declineIsAudited() {
        publish();
        long before = auditLogs.count();

        terms.decline("1.0");
        terms.decline("99");
        entityManager.flush();

        List<AuditLog> declines = auditLogs.findAll().stream()
                .filter(event -> "TERMS_DECLINED".equals(event.getAction()))
                .sorted(Comparator.comparing(AuditLog::getId))
                .toList();
        assertThat(auditLogs.count()).isEqualTo(before + 2);
        assertThat(declines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(declines.get(declines.size() - 2).getEntityId())
                .isEqualTo(versions.findByVersionIgnoreCase("1.0").orElseThrow().getId());
        assertThat(declines.get(declines.size() - 1).getEntityId()).isNull();
        assertThat(declines.get(declines.size() - 1).getDetails()).contains("99");
    }
}
