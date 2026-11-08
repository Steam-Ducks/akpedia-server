package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.akpedia.server.client.KeycloakAdminClient;
import com.akpedia.server.client.KeycloakRole;
import com.akpedia.server.client.KeycloakUser;
import com.akpedia.server.dto.AccountDeletionReplayResponse;
import com.akpedia.server.dto.AuditEntry;
import com.akpedia.server.dto.AuditKind;
import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.entity.ProfileChangeRequest;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.ChangeRequestStatus;
import com.akpedia.server.entity.enums.ProfileField;
import com.akpedia.server.exception.KeycloakUnavailableException;
import com.akpedia.server.exception.LastAdminException;
import com.akpedia.server.repository.AuditLogRepository;
import com.akpedia.server.repository.ProfileChangeRequestRepository;
import com.akpedia.server.repository.UserRepository;
import com.akpedia.server.security.KeycloakAccount;
import com.akpedia.server.security.UserProvisioningService;

/**
 * Deleting one's account and replaying the deletions, against the real schema and a log file of
 * the test's own, with Keycloak mocked.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AccountDeletionService.class, AccountDeletionReplay.class, AccountDeletionLog.class,
    AuditService.class, UserProvisioningService.class})
@TestPropertySource(properties = {
    "akpedia.account-deletion.log-file=target/test-account-deletions/${random.uuid}.jsonl",
    "akpedia.account-deletion.replay-on-startup=false"})
class AccountDeletionServiceTest {

    private static final KeycloakRole ADMIN = new KeycloakRole("r-adm", "ADMIN");

    @Autowired
    private AccountDeletionService accounts;

    @Autowired
    private AccountDeletionReplay replay;

    @Autowired
    private AccountDeletionLog deletionLog;

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private UserRepository users;

    @Autowired
    private ProfileChangeRequestRepository changeRequests;

    @Autowired
    private TestEntityManager entityManager;

    @MockBean
    private KeycloakAdminClient keycloak;

    private Sector sector;
    private User ana;

    @BeforeEach
    void setUp() {
        sector = entityManager.persist(new Sector("Setor-" + UUID.randomUUID(), null));
        ana = persistUser("Ana Souza", UUID.randomUUID().toString());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private User persistUser(String name, String keycloakId) {
        User user = new User(name, UUID.randomUUID() + "@akpedia.test", sector);
        user.setKeycloakId(keycloakId);
        return entityManager.persist(user);
    }

    /** A request from Ana, with her token, IP and browser. */
    private void anaIsCalling() {
        isCalling(ana, new MockHttpServletRequest("DELETE", "/api/v1/me"));
    }

    private static void isCalling(User user, MockHttpServletRequest request) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(user.getKeycloakId()).claim("email", user.getEmail()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("User-Agent", "Mozilla/5.0 (teste)");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private List<AuditLog> entriesOf(String keycloakId) {
        return auditLogs.findAll().stream().filter(entry -> keycloakId.equals(entry.getUserKeycloakId())).toList();
    }

    @Test
    @DisplayName("deleting: logged first, Keycloak account gone, row anonymized, audit pseudonymized")
    void deletesTheAccount() {
        String keycloakId = ana.getKeycloakId();
        Long id = ana.getId();
        String email = ana.getEmail();
        // An admin created Ana's account and looked her up by email, before she had a row.
        User admin = persistUser("Admin", UUID.randomUUID().toString());
        isCalling(admin, new MockHttpServletRequest("POST", "/api/v1/admin/users"));
        audit.record(admin.getId(), AuditService.USER_CREATED, AuditService.USER, null,
                AuditService.details("target_keycloak_id", keycloakId, "target_email", email, "roles", List.of()));
        MockHttpServletRequest search = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        search.setQueryString("search=" + email.replace("@", "%40").toUpperCase() + "&page=0");
        audit.recordRequest(search, 200, 5);
        MockHttpServletRequest otherSearch = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        otherSearch.setQueryString("search=bia");
        audit.recordRequest(otherSearch, 200, 5);
        // Her first request, logged without her row.
        AuditLog withoutRow = new AuditLog(null, AuditLog.HTTP_REQUEST, null, null);
        withoutRow.setUserKeycloakId(keycloakId);
        withoutRow.setUserEmail(email);
        withoutRow.setIpAddress("10.0.0.9");
        auditLogs.save(withoutRow);
        anaIsCalling();
        audit.record(id, AuditService.TERMS_ACCEPTED, AuditService.TERMS_VERSION, null, null);
        List<List<AccountDeletionLog.Entry>> logAtKeycloakCall = new ArrayList<>();
        willAnswer(invocation -> {
            logAtKeycloakCall.add(deletionLog.entries());
            return true;
        }).given(keycloak).deleteUser(keycloakId);

        accounts.deleteOwnAccount(keycloakId);
        // Her token still works for a few minutes: what it does is recorded without her too.
        Jwt stillValid = Jwt.withTokenValue("token").header("alg", "none")
                .subject(keycloakId).claim("email", email).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(stillValid));
        MockHttpServletRequest late = new MockHttpServletRequest("GET", "/api/v1/terms");
        late.setRemoteAddr("10.0.0.9");
        audit.recordRequest(late, 401, 1);

        // The log already had the deletion when Keycloak was asked to delete.
        assertThat(logAtKeycloakCall).singleElement().satisfies(entries -> assertThat(entries)
                .anySatisfy(entry -> {
                    assertThat(entry.keycloakId()).isEqualTo(keycloakId);
                    assertThat(entry.userId()).isEqualTo(id);
                    assertThat(entry.deletedBy()).isEqualTo("self");
                    assertThat(entry.time()).isNotBlank();
                }));
        assertThat(deletionLog.isDeleted(keycloakId)).isTrue();

        entityManager.clear();
        User row = users.findById(id).orElseThrow();
        assertThat(row.getName()).isEqualTo("Usuário removido");
        assertThat(row.getEmail()).isEqualTo("removido-" + id + "@removido.invalid");
        assertThat(row.getKeycloakId()).isNull();
        assertThat(row.isActive()).isFalse();
        assertThat(row.getDeletedAt()).isNotNull();

        List<AuditLog> entries = entriesOf(keycloakId);
        assertThat(entries).extracting(AuditLog::getAction)
                .contains(AuditService.TERMS_ACCEPTED, AuditService.ACCOUNT_DELETED, AuditLog.HTTP_REQUEST);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.getUserEmail()).isNull();
            assertThat(entry.getIpAddress()).isNull();
            assertThat(entry.getUserAgent()).isNull();
            assertThat(entry.getUser().getId()).isEqualTo(id);
        });
        // What the admin wrote about her keeps her Keycloak id, but not her email.
        List<AuditLog> byAdmin = auditLogs.findAll().stream()
                .filter(entry -> admin.getKeycloakId().equals(entry.getUserKeycloakId())).toList();
        assertThat(byAdmin).filteredOn(entry -> entry.getAction().equals(AuditService.USER_CREATED))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getDetails()).contains(keycloakId).doesNotContain(email).doesNotContain("target_email");
                    assertThat(entry.getUserEmail()).isEqualTo(admin.getEmail());
                });
        assertThat(byAdmin).filteredOn(entry -> entry.getAction().equals(AuditLog.HTTP_REQUEST))
                .extracting(AuditLog::getDetails)
                .containsExactlyInAnyOrder(null, "{\"query\": \"search=bia\"}");
        // The audit screen shows a pseudonym in her place, the same on all her entries.
        List<Long> entryIds = entries.stream().map(AuditLog::getId).toList();
        List<AuditEntry> shown = audit.search(AuditKind.ALL, null, null, null, null, 0, 200).content().stream()
                .filter(entry -> entryIds.contains(entry.id())).toList();
        assertThat(shown).hasSize(entries.size()).allSatisfy(entry -> {
            assertThat(entry.userPseudonym()).isEqualTo("Usuário removido #" + id);
            assertThat(entry.userName()).isNull();
            assertThat(entry.userEmail()).isNull();
        });
        assertThat(entries).filteredOn(entry -> entry.getAction().equals(AuditService.ACCOUNT_DELETED))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getEntityType()).isEqualTo("USER");
                    assertThat(entry.getEntityId()).isEqualTo(id);
                    assertThat(entry.getPath()).isEqualTo("/api/v1/me");
                });
    }

    @Test
    @DisplayName("an account that never had a row: its entries and the searches for it are pseudonymized too")
    void deletesAccountWithoutRow() {
        String keycloakId = UUID.randomUUID().toString();
        String email = "sem-linha-" + UUID.randomUUID() + "@akpedia.test";
        User admin = persistUser("Admin", UUID.randomUUID().toString());
        isCalling(admin, new MockHttpServletRequest("GET", "/api/v1/admin/users"));
        MockHttpServletRequest search = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        search.setQueryString("search=" + email);
        audit.recordRequest(search, 200, 5);
        AuditLog declined = new AuditLog(null, AuditService.TERMS_DECLINED, AuditService.TERMS_VERSION, null);
        declined.setUserKeycloakId(keycloakId);
        declined.setUserEmail(email);
        declined.setIpAddress("10.0.0.9");
        declined.setUserAgent("Mozilla/5.0 (teste)");
        auditLogs.save(declined);
        SecurityContextHolder.clearContext();

        accounts.deleteOwnAccount(keycloakId);

        entityManager.clear();
        assertThat(entriesOf(keycloakId)).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.getUser()).isNull();
            assertThat(entry.getUserEmail()).isNull();
            assertThat(entry.getIpAddress()).isNull();
            assertThat(entry.getUserAgent()).isNull();
        });
        assertThat(auditLogs.findAll()).extracting(AuditLog::getDetails).noneMatch(
                details -> details != null && details.contains(email));
        assertThat(audit.search(AuditKind.EVENT, AuditService.TERMS_DECLINED, null, null, null, 0, 200).content())
                .filteredOn(entry -> entry.id().equals(declined.getId()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.userPseudonym()).isEqualTo("Usuário removido #" + keycloakId.substring(0, 8));
                    assertThat(entry.userEmail()).isNull();
                });
    }

    @Test
    @DisplayName("the account's e-mail and CPF requests are deleted; the ones it decided as an admin stay, unlinked")
    void deletesChangeRequests() {
        User bia = persistUser("Bia Lima", UUID.randomUUID().toString());
        ProfileChangeRequest asked = entityManager.persist(
                new ProfileChangeRequest(ana, ProfileField.EMAIL, "nova@akpedia.test", "Casei"));
        ProfileChangeRequest decided = entityManager.persist(
                new ProfileChangeRequest(bia, ProfileField.CPF, "98765432100", null));
        decided.decide(ChangeRequestStatus.REJECTED, ana, "Confira o CPF", null);
        entityManager.flush();
        anaIsCalling();

        accounts.deleteOwnAccount(ana.getKeycloakId());

        entityManager.clear();
        assertThat(changeRequests.findById(asked.getId())).isEmpty();
        ProfileChangeRequest kept = changeRequests.findById(decided.getId()).orElseThrow();
        assertThat(kept.getDecidedBy()).isNull();
        assertThat(kept.getStatus()).isEqualTo(ChangeRequestStatus.REJECTED);
        assertThat(kept.getDecisionNote()).isEqualTo("Confira o CPF");
    }

    @Test
    @DisplayName("registered again with the same email, the user gets a new row; the old one stays anonymized")
    void registeredAgain() {
        String email = ana.getEmail();
        Long oldId = ana.getId();
        accounts.deleteOwnAccount(ana.getKeycloakId());
        entityManager.clear();

        String newKeycloakId = UUID.randomUUID().toString();
        Long newId = provisioning.provision(new KeycloakAccount(newKeycloakId, email, "Ana Souza", sector.getName()));

        assertThat(newId).isNotEqualTo(oldId);
        entityManager.clear();
        User again = users.findById(newId).orElseThrow();
        assertThat(again.getEmail()).isEqualTo(email);
        assertThat(again.getKeycloakId()).isEqualTo(newKeycloakId);
        assertThat(again.getDeletedAt()).isNull();
        User old = users.findById(oldId).orElseThrow();
        assertThat(old.getName()).isEqualTo("Usuário removido");
        assertThat(old.getKeycloakId()).isNull();
    }

    @Test
    @DisplayName("the only enabled admin cannot delete their account, and nothing is logged")
    void lastAdmin() {
        String keycloakId = ana.getKeycloakId();
        String otherAdmin = UUID.randomUUID().toString();
        given(keycloak.effectiveRealmRoles(keycloakId)).willReturn(List.of(ADMIN));
        given(keycloak.effectiveRealmRoles(otherAdmin)).willReturn(List.of(ADMIN));
        given(keycloak.users(null, 0, AccountDeletionService.ADMIN_SEARCH_PAGE)).willReturn(List.of(
                new KeycloakUser(keycloakId, "ana", "ana@x", "Ana", "S", true, null, Map.of()),
                new KeycloakUser(otherAdmin, "bia", "bia@x", "Bia", "S", false, null, Map.of())));

        assertThatThrownBy(() -> accounts.deleteOwnAccount(keycloakId)).isInstanceOf(LastAdminException.class);

        assertThat(deletionLog.isDeleted(keycloakId)).isFalse();
        verify(keycloak, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("an admin may leave while another enabled admin remains")
    void anotherAdminRemains() {
        String keycloakId = ana.getKeycloakId();
        String otherAdmin = UUID.randomUUID().toString();
        given(keycloak.effectiveRealmRoles(keycloakId)).willReturn(List.of(ADMIN));
        given(keycloak.effectiveRealmRoles(otherAdmin)).willReturn(List.of(ADMIN));
        given(keycloak.users(null, 0, AccountDeletionService.ADMIN_SEARCH_PAGE)).willReturn(List.of(
                new KeycloakUser(otherAdmin, "bia", "bia@x", "Bia", "S", true, null, Map.of())));

        accounts.deleteOwnAccount(keycloakId);

        verify(keycloak).deleteUser(keycloakId);
    }

    @Test
    @DisplayName("if Keycloak fails, the deletion stays in the log, to be finished by a retry or the replay")
    void keycloakFailure() {
        String keycloakId = ana.getKeycloakId();
        given(keycloak.deleteUser(keycloakId)).willThrow(new KeycloakUnavailableException("excluir o usuario", null));

        assertThatThrownBy(() -> accounts.deleteOwnAccount(keycloakId))
                .isInstanceOf(KeycloakUnavailableException.class);

        assertThat(deletionLog.entries()).extracting(AccountDeletionLog.Entry::keycloakId).contains(keycloakId);
    }

    @Test
    @DisplayName("replay deletes again what a restore brought back, and leaves the rest alone")
    void replay() {
        // Ana's deletion is in the log, but a restore brought her row and Keycloak account back.
        deletionLog.append(ana.getKeycloakId(), ana.getId());
        given(keycloak.deleteUser(ana.getKeycloakId())).willReturn(true);
        // Restored too, before it was linked to Keycloak: matched by its id.
        User bia = persistUser("Bia Lima", null);
        deletionLog.append(UUID.randomUUID().toString(), bia.getId());
        // Same id in the log, but linked to another account now: not hers to delete.
        User caio = persistUser("Caio Reis", UUID.randomUUID().toString());
        deletionLog.append(UUID.randomUUID().toString(), caio.getId());

        AccountDeletionReplayResponse first = replay.replay();

        assertThat(first.deletedAgain()).isGreaterThanOrEqualTo(2);
        entityManager.clear();
        assertThat(users.findById(ana.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(users.findById(ana.getId()).orElseThrow().getKeycloakId()).isNull();
        assertThat(users.findById(bia.getId()).orElseThrow().getName()).isEqualTo("Usuário removido");
        assertThat(users.findById(caio.getId()).orElseThrow().getName()).isEqualTo("Caio Reis");

        // Nothing came back since: nothing to do.
        given(keycloak.deleteUser(anyString())).willReturn(false);
        AccountDeletionReplayResponse second = replay.replay();

        assertThat(second.entries()).isEqualTo(first.entries());
        assertThat(second.deletedAgain()).isZero();
    }
}
