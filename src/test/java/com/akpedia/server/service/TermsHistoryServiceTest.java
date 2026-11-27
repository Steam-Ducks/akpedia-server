package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.akpedia.server.dto.OptionalPermissionDraft;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsHistoryDetail;
import com.akpedia.server.dto.TermsHistoryEntry;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.exception.TermsHistoryEntryNotFoundException;
import com.akpedia.server.repository.TermsHistoryRepository;

/** The admin's history of the terms, against the real schema: acceptances, later changes and refusals. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TermsHistoryService.class, TermsHistoryRepository.class, TermsService.class, AuditService.class,
        AccountDeletionLog.class, OptionalPermissionService.class})
class TermsHistoryServiceTest {

    @Autowired
    private TermsHistoryService history;

    @Autowired
    private TermsService terms;

    @Autowired
    private OptionalPermissionService permissions;

    @Autowired
    private TestEntityManager entityManager;

    private User admin;
    private User ana;
    private User bia;
    private TermsVersionResponse published;

    @BeforeEach
    void setUp() {
        Sector sector = entityManager.persist(new Sector("Setor-" + UUID.randomUUID(), null));
        admin = persist("Admin", sector);
        ana = persist("Ana", sector);
        bia = persist("Bia", sector);
        published = terms.publish(admin.getId(), new PublishTermsRequest("Termos", "Texto", List.of(
                new OptionalPermissionDraft(null, "Newsletter", "E-mails sobre novidades"),
                new OptionalPermissionDraft(null, "Análise de uso", "Métricas de uso"))));
    }

    @AfterEach
    void logOut() {
        SecurityContextHolder.clearContext();
    }

    private User persist(String name, Sector sector) {
        User user = new User(name, name.toLowerCase() + "." + UUID.randomUUID() + "@akpedia.test", sector);
        user.setKeycloakId(UUID.randomUUID().toString());
        return entityManager.persist(user);
    }

    private void logInAs(User user) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(user.getKeycloakId()).claim("email", user.getEmail()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private PageResponse<TermsHistoryEntry> of(User user) {
        entityManager.flush();
        return history.search(null, user.getEmail(), 0, 20);
    }

    private static TermsHistoryDetail.Permission permission(TermsHistoryDetail detail, String id) {
        return detail.permissions().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void listsAcceptancesThenLaterChangesNewestFirst() {
        terms.accept(ana.getId(), published.version(), Map.of("newsletter", true));
        permissions.choose(ana.getId(), "newsletter", false, published.version());

        PageResponse<TermsHistoryEntry> page = of(ana);

        assertThat(page.content()).extracting(TermsHistoryEntry::action)
                .containsExactly(TermsHistoryEntry.UPDATED, TermsHistoryEntry.ACCEPTED);
        assertThat(page.content()).allSatisfy(e -> {
            assertThat(e.version()).isEqualTo(published.version());
            assertThat(e.userId()).isEqualTo(ana.getId());
            assertThat(e.userName()).isEqualTo("Ana");
            assertThat(e.userEmail()).isEqualTo(ana.getEmail());
        });
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(history.search(published.version().toUpperCase(), "ANA." + ana.getEmail().substring(4, 12), 0, 20)
                .totalElements()).isEqualTo(2);
        assertThat(history.search(null, ana.getEmail(), 1, 1).content()).extracting(TermsHistoryEntry::action)
                .containsExactly(TermsHistoryEntry.ACCEPTED);
    }

    @Test
    void anAcceptanceShowsNothingBeforeAndTheChoicesMadeWithItAfter() {
        terms.accept(ana.getId(), published.version(), Map.of("newsletter", true));
        permissions.choose(ana.getId(), "newsletter", false, published.version());
        TermsHistoryEntry accepted = of(ana).content().get(1);

        TermsHistoryDetail detail = history.detail(TermsHistoryEntry.ACCEPTED, accepted.id());

        assertThat(detail.version()).isEqualTo(published.version());
        assertThat(detail.userName()).isEqualTo("Ana");
        assertThat(detail.permissions()).extracting(TermsHistoryDetail.Permission::grantedBefore).containsOnlyNulls();
        assertThat(permission(detail, "newsletter").grantedAfter()).isTrue();
        assertThat(permission(detail, "analise-de-uso").grantedAfter()).isFalse();
        assertThat(detail.permissions()).noneMatch(TermsHistoryDetail.Permission::changed);
    }

    @Test
    void aChangeMarksThePermissionItTurnedOnOrOff() {
        terms.accept(ana.getId(), published.version(), Map.of());
        permissions.choose(ana.getId(), "analise-de-uso", true, published.version());
        TermsHistoryEntry updated = of(ana).content().get(0);

        TermsHistoryDetail detail = history.detail(TermsHistoryEntry.UPDATED, updated.id());

        assertThat(permission(detail, "analise-de-uso").changed()).isTrue();
        assertThat(permission(detail, "analise-de-uso").grantedBefore()).isFalse();
        assertThat(permission(detail, "analise-de-uso").grantedAfter()).isTrue();
        assertThat(permission(detail, "newsletter").changed()).isFalse();
        assertThat(permission(detail, "newsletter").grantedBefore()).isFalse();
        assertThat(permission(detail, "newsletter").grantedAfter()).isFalse();
    }

    @Test
    void aRefusalShowsWhoRefusedAndAnAcceptanceAfterItBothStay() {
        logInAs(bia);
        terms.decline(published.version());
        terms.accept(bia.getId(), published.version(), Map.of());

        List<TermsHistoryEntry> entries = of(bia).content();

        assertThat(entries).extracting(TermsHistoryEntry::action)
                .containsExactly(TermsHistoryEntry.ACCEPTED, TermsHistoryEntry.DECLINED);
        assertThat(entries.get(1).userName()).isEqualTo("Bia");
        // Declined before choosing anything: nothing before, nothing after, even though she accepted later.
        TermsHistoryDetail detail = history.detail(TermsHistoryEntry.DECLINED, entries.get(1).id());
        assertThat(detail.version()).isEqualTo(published.version());
        assertThat(detail.permissions()).extracting(TermsHistoryDetail.Permission::grantedBefore).containsOnlyNulls();
        assertThat(detail.permissions()).extracting(TermsHistoryDetail.Permission::grantedAfter).containsOnlyNulls();
    }

    @Test
    void unknownEntriesAndPagesAreRefused() {
        terms.accept(ana.getId(), published.version(), Map.of());
        TermsHistoryEntry accepted = of(ana).content().get(0);

        assertThatThrownBy(() -> history.detail(TermsHistoryEntry.UPDATED, Long.MAX_VALUE))
                .isInstanceOf(TermsHistoryEntryNotFoundException.class);
        assertThatThrownBy(() -> history.detail(TermsHistoryEntry.ACCEPTED, Long.MAX_VALUE))
                .isInstanceOf(TermsHistoryEntryNotFoundException.class);
        assertThatThrownBy(() -> history.detail("deleted", accepted.id()))
                .isInstanceOf(TermsHistoryEntryNotFoundException.class);
        assertThatThrownBy(() -> history.search(null, null, -1, 20)).isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> history.search(null, null, 0, 101)).isInstanceOf(InvalidPageRequestException.class);
    }

    @Test
    void aSecondChangeOfTheSamePermissionStartsFromTheFirst() {
        terms.accept(ana.getId(), published.version(), Map.of("newsletter", true));
        permissions.choose(ana.getId(), "newsletter", false, published.version());
        permissions.choose(ana.getId(), "newsletter", true, published.version());
        List<TermsHistoryEntry> entries = of(ana).content();

        TermsHistoryDetail latest = history.detail(TermsHistoryEntry.UPDATED, entries.get(0).id());
        TermsHistoryDetail first = history.detail(TermsHistoryEntry.UPDATED, entries.get(1).id());

        assertThat(permission(first, "newsletter").grantedBefore()).isTrue();
        assertThat(permission(first, "newsletter").grantedAfter()).isFalse();
        assertThat(permission(latest, "newsletter").grantedBefore()).isFalse();
        assertThat(permission(latest, "newsletter").grantedAfter()).isTrue();
    }
}
