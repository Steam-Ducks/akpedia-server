package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsStatusResponse;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.exception.TermsChangedException;
import com.akpedia.server.repository.TermsAcceptanceRepository;
import com.akpedia.server.repository.TermsVersionRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TermsService.class)
class TermsServiceTest {

    @Autowired
    private TermsService terms;

    @Autowired
    private TermsVersionRepository versions;

    @Autowired
    private TermsAcceptanceRepository acceptances;

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

    private TermsVersionResponse publish(String content) {
        return terms.publish(admin.getId(), new PublishTermsRequest("Termos de uso", content));
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
    @DisplayName("publishing numbers the versions 1, 2, ... and records who published")
    void publishNumbersVersions() {
        TermsVersionResponse first = publish("  Texto 1  ");
        TermsVersionResponse second = publish("Texto 2");

        assertThat(first.version()).isEqualTo(1);
        assertThat(first.content()).isEqualTo("Texto 1");
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.publishedBy()).isEqualTo("Admin Akpedia");
        assertThat(second.publishedAt()).isNotNull();
        assertThat(terms.history()).extracting(TermsVersionResponse::version).containsExactly(2, 1);
    }

    @Test
    @DisplayName("once terms are published, everyone must accept them, the publisher included")
    void publishedTermsMustBeAccepted() {
        publish("Texto");

        assertThat(terms.mustAccept(ana.getKeycloakId())).isTrue();
        assertThat(terms.mustAccept(admin.getKeycloakId())).isTrue();
        // An account without a row yet has accepted nothing either.
        assertThat(terms.mustAccept("never-logged-in")).isTrue();
    }

    @Test
    @DisplayName("accepting the current version lets the user through, until a new one is published")
    void acceptingCurrentVersion() {
        publish("Texto 1");

        var accepted = terms.accept(ana.getId(), 1);

        assertThat(accepted.version()).isEqualTo(1);
        assertThat(accepted.acceptedAt()).isNotNull();
        assertThat(terms.mustAccept(ana.getKeycloakId())).isFalse();
        assertThat(terms.status(ana.getKeycloakId()).accepted().version()).isEqualTo(1);

        publish("Texto 2");

        assertThat(terms.mustAccept(ana.getKeycloakId())).isTrue();
        assertThat(terms.status(ana.getKeycloakId()).accepted().version()).isEqualTo(1);
        assertThat(terms.status(ana.getKeycloakId()).current().version()).isEqualTo(2);
    }

    @Test
    @DisplayName("accepting a version that is no longer current is refused")
    void acceptingOldVersionIsRefused() {
        publish("Texto 1");
        publish("Texto 2");

        assertThatThrownBy(() -> terms.accept(ana.getId(), 1)).isInstanceOf(TermsChangedException.class);
        assertThat(terms.mustAccept(ana.getKeycloakId())).isTrue();
    }

    @Test
    @DisplayName("accepting when nothing was published is refused")
    void acceptingWithoutTermsIsRefused() {
        assertThatThrownBy(() -> terms.accept(ana.getId(), 1)).isInstanceOf(TermsChangedException.class);
    }

    @Test
    @DisplayName("every acceptance is kept")
    void acceptancesAreAppendOnly() {
        publish("Texto 1");
        terms.accept(ana.getId(), 1);
        terms.accept(ana.getId(), 1);
        publish("Texto 2");
        terms.accept(ana.getId(), 2);

        assertThat(acceptances.count()).isEqualTo(3);
        assertThat(terms.status(ana.getKeycloakId()).accepted().version()).isEqualTo(2);
    }
}
