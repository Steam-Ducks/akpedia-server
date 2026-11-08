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

import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsStatusResponse;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.entity.TermsVersion;
import com.akpedia.server.exception.InvalidTermsException;
import com.akpedia.server.exception.TermsChangedException;
import com.akpedia.server.exception.TermsVersionExistsException;
import com.akpedia.server.repository.AuditLogRepository;
import com.akpedia.server.repository.TermsAcceptanceRepository;
import com.akpedia.server.repository.TermsVersionRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TermsService.class, AuditService.class, AccountDeletionLog.class})
class TermsServiceTest {

    @Autowired
    private TermsService terms;

    @Autowired
    private TermsVersionRepository versions;

    @Autowired
    private TermsAcceptanceRepository acceptances;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private TestEntityManager entityManager;

    private User admin;
    private User ana;

    @BeforeEach
    void setUp() {
        // Starts from no terms at all, whatever the dev database holds; the test's rollback puts it back.
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

    private TermsVersionResponse publish(String version) {
        return publish(version, "Texto " + version);
    }

    private TermsVersionResponse publish(String version, String content) {
        return terms.publish(admin.getId(), new PublishTermsRequest(version, "Termos de uso", content));
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
    @DisplayName("publishing keeps the version the admin typed, trimmed, and records who published")
    void publishKeepsTypedVersion() {
        TermsVersionResponse first = publish("  1.0  ", "  Texto 1  ");
        TermsVersionResponse second = publish("2026.1-rc_2");

        assertThat(first.version()).isEqualTo("1.0");
        assertThat(first.content()).isEqualTo("Texto 1");
        assertThat(second.version()).isEqualTo("2026.1-rc_2");
        assertThat(second.publishedBy()).isEqualTo("Admin Akpedia");
        assertThat(second.publishedAt()).isNotNull();
        assertThat(terms.history()).extracting(TermsVersionResponse::version).containsExactly("2026.1-rc_2", "1.0");
    }

    @Test
    @DisplayName("the current version is the newest published, not the highest: 9 after 10 is current")
    void currentIsNewestPublished() {
        publish("10");
        publish("9");
        publish("1.5");

        assertThat(terms.status(ana.getKeycloakId()).current().version()).isEqualTo("1.5");
        assertThat(terms.history()).extracting(TermsVersionResponse::version).containsExactly("1.5", "9", "10");
        assertThatThrownBy(() -> terms.accept(ana.getId(), "10")).isInstanceOf(TermsChangedException.class);
        assertThat(terms.accept(ana.getId(), "1.5").version()).isEqualTo("1.5");
        assertThat(terms.mustAccept(ana.getKeycloakId())).isFalse();
    }

    @Test
    @DisplayName("a version out of format is refused: blank, too long, or with other characters")
    void publishRefusesBadVersion() {
        for (String bad : new String[] {null, "", "   ", "x".repeat(21), "1 0", "1,0", "v1/2", "versão", "1.0!"}) {
            assertThatThrownBy(() -> publish(bad))
                    .as("version %s", bad)
                    .isInstanceOf(InvalidTermsException.class)
                    .hasMessage("Informe a versão (até 20 caracteres: letras, números, ponto, hífen ou sublinhado).");
        }
        assertThat(publish("x".repeat(20)).version()).hasSize(20);
    }

    @Test
    @DisplayName("a version published before, in any case and even if not the latest, is refused")
    void publishRefusesRepeatedVersion() {
        publish("v1.0");
        publish("2.0");

        assertThatThrownBy(() -> publish("v1.0")).isInstanceOf(TermsVersionExistsException.class);
        assertThatThrownBy(() -> publish("  V1.0 "))
                .isInstanceOf(TermsVersionExistsException.class)
                .hasMessage("Já existe a versão V1.0. Use um número de versão novo.");
        assertThat(terms.history()).hasSize(2);
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
        publish("1.0");

        assertThat(terms.mustAccept(ana.getKeycloakId())).isTrue();
        assertThat(terms.mustAccept(admin.getKeycloakId())).isTrue();
        // An account without a row yet has accepted nothing either.
        assertThat(terms.mustAccept("never-logged-in")).isTrue();
    }

    @Test
    @DisplayName("accepting the current version lets the user through, until a new one is published")
    void acceptingCurrentVersion() {
        publish("1.0");

        var accepted = terms.accept(ana.getId(), "1.0");

        assertThat(accepted.version()).isEqualTo("1.0");
        assertThat(accepted.acceptedAt()).isNotNull();
        assertThat(terms.mustAccept(ana.getKeycloakId())).isFalse();
        assertThat(terms.status(ana.getKeycloakId()).accepted().version()).isEqualTo("1.0");

        publish("1.1");

        assertThat(terms.mustAccept(ana.getKeycloakId())).isTrue();
        assertThat(terms.status(ana.getKeycloakId()).accepted().version()).isEqualTo("1.0");
        assertThat(terms.status(ana.getKeycloakId()).current().version()).isEqualTo("1.1");
    }

    @Test
    @DisplayName("accepting a version that is no longer current is refused")
    void acceptingOldVersionIsRefused() {
        publish("1.0");
        publish("2.0");

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
    @DisplayName("every acceptance is kept")
    void acceptancesAreAppendOnly() {
        publish("1.0");
        terms.accept(ana.getId(), "1.0");
        terms.accept(ana.getId(), "1.0");
        publish("2.0");
        terms.accept(ana.getId(), "2.0");

        assertThat(acceptances.count()).isEqualTo(3);
        assertThat(terms.status(ana.getKeycloakId()).accepted().version()).isEqualTo("2.0");
    }

    @Test
    @DisplayName("the admin's users screen gets the version each user accepted last, not the highest")
    void lastAcceptedVersionsAreTheLatest() {
        publish("9");
        terms.accept(ana.getId(), "9");
        terms.accept(admin.getId(), "9");
        publish("10");
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
        TermsVersionResponse published = publish("1.0");
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
    @DisplayName("a refused publish or acceptance leaves no event behind")
    void refusedActionsAreNotAudited() {
        assertThatThrownBy(() -> terms.accept(ana.getId(), "1.0")).isInstanceOf(TermsChangedException.class);
        assertThatThrownBy(() -> publish("1 0")).isInstanceOf(InvalidTermsException.class);

        assertThat(eventsOf(ana.getId())).isEmpty();
        assertThat(eventsOf(admin.getId())).isEmpty();
    }

    @Test
    @DisplayName("declining is recorded against the version shown, or none if it does not exist")
    void declineIsAudited() {
        publish("1.0");
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
