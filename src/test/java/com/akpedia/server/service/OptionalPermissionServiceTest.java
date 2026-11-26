package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import com.akpedia.server.dto.AdminOptionalPermission;
import com.akpedia.server.dto.OptionalPermissionDraft;
import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.dto.UserOptionalPermission;
import com.akpedia.server.dto.UserOptionalPermissionsResponse;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.exception.InvalidOptionalPermissionException;
import com.akpedia.server.exception.OptionalPermissionNotFoundException;
import com.akpedia.server.exception.OptionalPermissionsChangedException;

/** Optional permissions as part of the terms of use, against the real schema. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OptionalPermissionService.class, TermsService.class, AuditService.class})
@MockBean(AccountDeletionLog.class)
class OptionalPermissionServiceTest {

    @Autowired
    private OptionalPermissionService service;

    @Autowired
    private TermsService terms;

    @Autowired
    private TestEntityManager entityManager;

    private User ana;
    private User admin;

    @BeforeEach
    void setUp() {
        Sector sector = entityManager.persist(new Sector("Setor-" + UUID.randomUUID(), null));
        ana = persist("Ana", sector);
        admin = persist("Admin", sector);
    }

    private User persist(String name, Sector sector) {
        User user = new User(name, UUID.randomUUID() + "@akpedia.test", sector);
        user.setKeycloakId(UUID.randomUUID().toString());
        return entityManager.persist(user);
    }

    private TermsVersionResponse publish(OptionalPermissionDraft... drafts) {
        return terms.publish(admin.getId(), new PublishTermsRequest("Termos", "Texto", List.of(drafts)));
    }

    private static OptionalPermissionDraft draft(String id, String label) {
        return new OptionalPermissionDraft(id, label, label + " descrição");
    }

    private UserOptionalPermission permission(UserOptionalPermissionsResponse set, String id) {
        return set.permissions().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void publishingTheTermsNamesNewPermissionsAndKeepsExistingIds() {
        TermsVersionResponse first = publish(draft(null, "Newsletter mensal"), draft(null, "Análise de uso"));
        assertThat(first.optionalPermissions()).extracting(p -> p.id())
                .containsExactly("newsletter-mensal", "analise-de-uso");

        TermsVersionResponse second = publish(draft("newsletter-mensal", "Newsletter semanal"),
                draft(null, "Newsletter mensal"));
        assertThat(second.optionalPermissions()).extracting(p -> p.id())
                .containsExactly("newsletter-mensal", "newsletter-mensal-2");
    }

    @Test
    void refusesIdsNotInTheCurrentVersionOrRepeated() {
        publish(draft(null, "Newsletter"));
        assertThatThrownBy(() -> publish(draft("unknown", "X")))
                .isInstanceOf(InvalidOptionalPermissionException.class);
        assertThatThrownBy(() -> publish(draft("newsletter", "A"), draft("newsletter", "B")))
                .isInstanceOf(InvalidOptionalPermissionException.class);
    }

    @Test
    void acceptingRecordsTheChoicesEverythingElseOff() {
        String version = publish(draft(null, "Newsletter"), draft(null, "Análise")).version();

        terms.accept(ana.getId(), version, Map.of("newsletter", true));

        UserOptionalPermissionsResponse set = service.forUser(ana.getId());
        assertThat(permission(set, "newsletter")).matches(p -> p.granted() && p.decided());
        assertThat(permission(set, "analise")).matches(p -> !p.granted() && p.decided());
        assertThat(service.forAdmin().permissions().get(0).grantedUsers())
                .extracting(AdminOptionalPermission.GrantedUser::userId).contains(ana.getId());
    }

    @Test
    void aNewVersionOfTheTermsResetsEveryChoiceAndMarksWhatChanged() {
        String first = publish(draft(null, "Newsletter"), draft(null, "Análise")).version();
        terms.accept(ana.getId(), first, Map.of("newsletter", true));

        String second = publish(draft("newsletter", "Newsletter semanal"), draft("analise", "Análise"),
                draft(null, "Pesquisa")).version();

        UserOptionalPermissionsResponse set = service.forUser(ana.getId());
        assertThat(set.version()).isEqualTo(second);
        assertThat(set.permissions()).allMatch(p -> !p.granted() && !p.decided());
        assertThat(permission(set, "newsletter").change()).isEqualTo("changed");
        assertThat(permission(set, "analise").change()).isNull();
        assertThat(permission(set, "pesquisa").change()).isEqualTo("new");
        assertThatThrownBy(() -> service.choose(ana.getId(), "newsletter", true, first))
                .isInstanceOf(OptionalPermissionsChangedException.class);
    }

    @Test
    void choicesChangeAnyTimeForTheCurrentVersion() {
        String version = publish(draft(null, "Newsletter")).version();
        terms.accept(ana.getId(), version, Map.of());

        service.choose(ana.getId(), "newsletter", true, version);

        assertThat(permission(service.forUser(ana.getId()), "newsletter").granted()).isTrue();
        assertThatThrownBy(() -> service.choose(ana.getId(), "removed", true, version))
                .isInstanceOf(OptionalPermissionNotFoundException.class);
    }

    @Test
    void revokeAllTurnsOffEverythingTheUserAllowed() {
        String version = publish(draft(null, "Newsletter"), draft(null, "Análise")).version();
        terms.accept(ana.getId(), version, Map.of("newsletter", true, "analise", true));

        service.revokeAll(ana.getId());

        assertThat(service.forUser(ana.getId()).permissions()).allMatch(p -> !p.granted());
    }

    @Test
    void slugDropsAccents() {
        assertThat(OptionalPermissionService.slug("Análise de uso!")).isEqualTo("analise-de-uso");
    }
}
