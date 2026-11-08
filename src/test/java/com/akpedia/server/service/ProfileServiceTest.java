package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import com.akpedia.server.client.KeycloakAdminClient;
import com.akpedia.server.client.KeycloakRole;
import com.akpedia.server.client.KeycloakUser;
import com.akpedia.server.dto.AdminProfileUpdateRequest;
import com.akpedia.server.dto.ChangeRequest;
import com.akpedia.server.dto.ChangeRequestStatusFilter;
import com.akpedia.server.dto.NewChangeRequest;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.Profile;
import com.akpedia.server.dto.UpdateProfileRequest;
import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.entity.ProfileChangeRequest;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.ChangeRequestStatus;
import com.akpedia.server.entity.enums.ProfileField;
import com.akpedia.server.exception.ChangeRequestNotFoundException;
import com.akpedia.server.exception.ChangeRequestNotPendingException;
import com.akpedia.server.exception.ChangeRequestPendingException;
import com.akpedia.server.exception.InvalidChangeRequestException;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.exception.InvalidProfileException;
import com.akpedia.server.exception.InvalidUserException;
import com.akpedia.server.exception.NothingChangedException;
import com.akpedia.server.exception.UserExistsException;
import com.akpedia.server.repository.AuditLogRepository;
import com.akpedia.server.repository.ProfileChangeRequestRepository;
import com.akpedia.server.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Editing the profile and the e-mail and CPF change requests, against the real schema, with
 * Keycloak mocked. Every audit entry names the fields, never their values.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProfileService.class, AuditService.class, AccountDeletionLog.class})
@TestPropertySource(properties = "akpedia.account-deletion.log-file=target/test-account-deletions/${random.uuid}.jsonl")
class ProfileServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CPF = "12345678901";
    private static final String NEW_CPF = "98765432100";

    @Autowired
    private ProfileService profiles;

    @Autowired
    private ProfileChangeRequestRepository requests;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private UserRepository users;

    @Autowired
    private TestEntityManager entityManager;

    @MockBean
    private KeycloakAdminClient keycloak;

    private Sector sector;
    private User ana;
    private User admin;
    private String newEmail;

    @BeforeEach
    void setUp() {
        sector = entityManager.persist(new Sector("Setor-" + UUID.randomUUID(), null));
        ana = persistUser("Ana Souza");
        admin = persistUser("Admin Akpedia");
        newEmail = "nova-" + UUID.randomUUID() + "@akpedia.test";
        given(keycloak.user(ana.getKeycloakId())).willReturn(account(ana, "Setor 1"));
        given(keycloak.effectiveRealmRoles(anyString()))
                .willReturn(List.of(new KeycloakRole("r-std", "STANDARD"), new KeycloakRole("r-def", "default-roles")));
        given(keycloak.usersWithEmail(anyString())).willReturn(List.of());
    }

    private User persistUser(String name) {
        User user = new User(name, UUID.randomUUID() + "@akpedia.test", sector);
        user.setKeycloakId(UUID.randomUUID().toString());
        return entityManager.persist(user);
    }

    private static KeycloakUser account(User user, String sectorName) {
        String[] names = user.getName().split(" ", 2);
        return new KeycloakUser(user.getKeycloakId(), user.getEmail(), user.getEmail(), names[0], names[1], true,
                1_762_000_000_000L, Map.of("cpf", List.of(CPF), "sector", List.of(sectorName)));
    }

    /** What the service would send Keycloak, applied to a representation like the one it reads. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sentToKeycloak(String keycloakId, String email) {
        ArgumentCaptor<Consumer<Map<String, Object>>> change = ArgumentCaptor.forClass(Consumer.class);
        verify(keycloak).updateUser(eq(keycloakId), change.capture(), email == null ? isNull() : eq(email));
        Map<String, Object> representation = new LinkedHashMap<>();
        representation.put("id", keycloakId);
        representation.put("firstName", "Ana");
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put("cpf", new ArrayList<>(List.of(CPF)));
        attributes.put("sector", new ArrayList<>(List.of("Setor 1")));
        representation.put("attributes", attributes);
        change.getValue().accept(representation);
        return representation;
    }

    /** This test's events of that kind: by Ana or the admin, or about Ana's row. */
    private List<AuditLog> events(String action) {
        return auditLogs.findAll().stream()
                .filter(entry -> action.equals(entry.getAction()))
                .filter(entry -> ana.getId().equals(entry.getEntityId()) || (entry.getUser() != null
                        && List.of(ana.getId(), admin.getId()).contains(entry.getUser().getId())))
                .toList();
    }

    private static Map<String, Object> details(AuditLog entry) {
        try {
            return JSON.readValue(entry.getDetails(), new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    private ProfileChangeRequest persistRequest(User user, ProfileField field, String value) {
        return entityManager.persistAndFlush(new ProfileChangeRequest(user, field, value, null));
    }

    @Test
    @DisplayName("the profile comes from Keycloak, with the known roles and what may be edited or requested")
    void readsProfile() {
        Profile profile = profiles.profile(ana.getKeycloakId());

        assertThat(profile.firstName()).isEqualTo("Ana");
        assertThat(profile.lastName()).isEqualTo("Souza");
        assertThat(profile.email()).isEqualTo(ana.getEmail());
        assertThat(profile.cpf()).isEqualTo(CPF);
        assertThat(profile.sector()).isEqualTo("Setor 1");
        assertThat(profile.roles()).containsExactly("STANDARD");
        assertThat(profile.editable()).containsExactly("first_name", "last_name", "sector");
        assertThat(profile.requestable()).containsExactly("email", "cpf");
    }

    @Test
    @DisplayName("editing name and sector: Keycloak gets them with the CPF kept, the row follows, the audit names the fields")
    void updatesOwnProfile() {
        profiles.update(ana.getKeycloakId(), ana.getId(), new UpdateProfileRequest(" Ana Maria ", "Souza", "Setor 3"));

        Map<String, Object> sent = sentToKeycloak(ana.getKeycloakId(), null);
        assertThat(sent).containsEntry("firstName", "Ana Maria").containsEntry("lastName", "Souza");
        assertThat(sent.get("attributes")).isEqualTo(Map.of("cpf", List.of(CPF), "sector", List.of("Setor 3")));
        entityManager.flush();
        entityManager.clear();
        User row = users.findById(ana.getId()).orElseThrow();
        assertThat(row.getName()).isEqualTo("Ana Maria Souza");
        assertThat(row.getSector().getName()).isEqualTo("Setor 3");
        assertThat(events(AuditService.PROFILE_UPDATED)).singleElement().satisfies(entry -> {
            assertThat(details(entry)).isEqualTo(Map.of("fields", List.of("first_name", "sector")));
            assertThat(entry.getEntityId()).isEqualTo(ana.getId());
        });
    }

    @Test
    @DisplayName("editing: nothing_changed when it is all as it was; invalid_profile for a bad field or Keycloak's refusal")
    void updateRefusals() {
        String id = ana.getKeycloakId();
        assertThatThrownBy(() -> profiles.update(id, ana.getId(), new UpdateProfileRequest("Ana", "Souza", "Setor 1")))
                .isInstanceOf(NothingChangedException.class);
        assertThatThrownBy(() -> profiles.update(id, ana.getId(), new UpdateProfileRequest("Ana", " ", "Setor 1")))
                .isInstanceOf(InvalidProfileException.class).hasMessage("Informe o sobrenome.");
        assertThatThrownBy(() -> profiles.update(id, ana.getId(), new UpdateProfileRequest("Ana", "Souza", "Setor 9")))
                .isInstanceOf(InvalidProfileException.class).hasMessageContaining("Setor inválido");
        assertThatThrownBy(() -> profiles.update(id, ana.getId(),
                new UpdateProfileRequest("A".repeat(256), "Souza", "Setor 1")))
                .isInstanceOf(InvalidProfileException.class).hasMessageContaining("255");
        willThrow(new InvalidUserException("O Keycloak recusou: firstName"))
                .given(keycloak).updateUser(eq(id), any(), any());
        assertThatThrownBy(() -> profiles.update(id, ana.getId(), new UpdateProfileRequest("Ana$", "Souza", "Setor 1")))
                .isInstanceOf(InvalidProfileException.class).hasMessageContaining("firstName");
        assertThat(events(AuditService.PROFILE_UPDATED)).isEmpty();
    }

    @Test
    @DisplayName("asking for a new e-mail: pending, audited with the field only; a second one is change_request_pending")
    void requestsEmail() {
        ChangeRequest request = profiles.request(ana.getKeycloakId(), ana.getId(),
                new NewChangeRequest("email", " " + newEmail.toUpperCase() + " ", "  Casei  "));

        assertThat(request.field()).isEqualTo("email");
        assertThat(request.newValue()).isEqualTo(newEmail);
        assertThat(request.reason()).isEqualTo("Casei");
        assertThat(request.status()).isEqualTo(ChangeRequestStatus.PENDING);
        assertThat(request.user().id()).isEqualTo(ana.getKeycloakId());
        assertThat(request.user().email()).isEqualTo(ana.getEmail());
        assertThat(events(AuditService.PROFILE_CHANGE_REQUESTED)).singleElement().satisfies(entry -> {
            assertThat(details(entry)).isEqualTo(Map.of("field", "email"));
            assertThat(entry.getEntityType()).isEqualTo(AuditService.PROFILE_CHANGE_REQUEST);
            assertThat(entry.getEntityId()).isEqualTo(request.id());
        });
        assertThatThrownBy(() -> profiles.request(ana.getKeycloakId(), ana.getId(),
                new NewChangeRequest("email", "outra-" + newEmail, null)))
                .isInstanceOf(ChangeRequestPendingException.class);
        // The CPF is another field: it may be asked for meanwhile.
        assertThat(profiles.request(ana.getKeycloakId(), ana.getId(), new NewChangeRequest("cpf", "987.654.321-00", null))
                .newValue()).isEqualTo(NEW_CPF);
        assertThat(profiles.requests(ana.getId())).extracting(ChangeRequest::field).containsExactly("cpf", "email");
    }

    @Test
    @DisplayName("asking: invalid_change_request for a bad field, value, the current value or a long reason; user_exists")
    void requestRefusals() {
        String id = ana.getKeycloakId();
        Long row = ana.getId();
        assertThatThrownBy(() -> profiles.request(id, row, new NewChangeRequest("name", "x", null)))
                .isInstanceOf(InvalidChangeRequestException.class);
        assertThatThrownBy(() -> profiles.request(id, row, new NewChangeRequest("email", "nao-e-email", null)))
                .isInstanceOf(InvalidChangeRequestException.class).hasMessage("Informe um e-mail válido.");
        assertThatThrownBy(() -> profiles.request(id, row, new NewChangeRequest("cpf", "123", null)))
                .isInstanceOf(InvalidChangeRequestException.class).hasMessageContaining("11 dígitos");
        assertThatThrownBy(() -> profiles.request(id, row, new NewChangeRequest("email", ana.getEmail().toUpperCase(), null)))
                .isInstanceOf(InvalidChangeRequestException.class).hasMessage("O novo e-mail é igual ao atual.");
        assertThatThrownBy(() -> profiles.request(id, row, new NewChangeRequest("cpf", CPF, null)))
                .isInstanceOf(InvalidChangeRequestException.class).hasMessage("O novo CPF é igual ao atual.");
        assertThatThrownBy(() -> profiles.request(id, row, new NewChangeRequest("cpf", NEW_CPF, "x".repeat(501))))
                .isInstanceOf(InvalidChangeRequestException.class).hasMessageContaining("500");
        given(keycloak.usersWithEmail(newEmail)).willReturn(List.of(account(admin, "Setor 1")));
        assertThatThrownBy(() -> profiles.request(id, row, new NewChangeRequest("email", newEmail, null)))
                .isInstanceOf(UserExistsException.class);
        assertThat(requests.findByUserIdOrderByCreatedAtDescIdDesc(row)).isEmpty();
    }

    @Test
    @DisplayName("the database keeps one pending request per user and field")
    void onePendingPerField() {
        persistRequest(ana, ProfileField.EMAIL, newEmail);

        assertThatThrownBy(() -> requests.saveAndFlush(new ProfileChangeRequest(ana, ProfileField.EMAIL, "b@x.io", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("cancelling: only one's own pending request, which is deleted")
    void cancels() {
        ProfileChangeRequest mine = persistRequest(ana, ProfileField.CPF, NEW_CPF);
        ProfileChangeRequest decided = persistRequest(ana, ProfileField.EMAIL, newEmail);
        decided.decide(ChangeRequestStatus.REJECTED, admin, null, null);
        entityManager.flush();

        assertThatThrownBy(() -> profiles.cancel(admin.getId(), mine.getId()))
                .isInstanceOf(ChangeRequestNotFoundException.class);
        assertThatThrownBy(() -> profiles.cancel(ana.getId(), decided.getId()))
                .isInstanceOf(ChangeRequestNotPendingException.class);
        profiles.cancel(ana.getId(), mine.getId());

        assertThat(requests.findById(mine.getId())).isEmpty();
        assertThat(events(AuditService.PROFILE_CHANGE_CANCELLED)).singleElement()
                .satisfies(entry -> assertThat(details(entry)).isEqualTo(Map.of("field", "cpf")));
    }

    @Test
    @DisplayName("approving an e-mail: Keycloak gets it verified, the row follows, the audit names field and user only")
    void approvesEmail() {
        ProfileChangeRequest request = persistRequest(ana, ProfileField.EMAIL, newEmail);

        ChangeRequest approved = profiles.approve(request.getId(), admin.getId());

        Map<String, Object> sent = sentToKeycloak(ana.getKeycloakId(), newEmail);
        assertThat(sent).containsEntry("email", newEmail).containsEntry("emailVerified", true);
        assertThat(approved.status()).isEqualTo(ChangeRequestStatus.APPROVED);
        assertThat(approved.decidedByName()).isEqualTo("Admin Akpedia");
        assertThat(approved.decidedAt()).isNotNull();
        entityManager.flush();
        assertThat(users.findById(ana.getId()).orElseThrow().getEmail()).isEqualTo(newEmail);
        assertThat(events(AuditService.PROFILE_CHANGE_APPROVED)).singleElement().satisfies(entry -> {
            assertThat(details(entry)).isEqualTo(Map.of("field", "email", "target_keycloak_id", ana.getKeycloakId()));
            assertThat(entry.getUser().getId()).isEqualTo(admin.getId());
        });
        assertThatThrownBy(() -> profiles.approve(request.getId(), admin.getId()))
                .isInstanceOf(ChangeRequestNotPendingException.class);
    }

    @Test
    @DisplayName("approving an e-mail taken meanwhile is user_exists, and the request stays pending")
    void approveTakenEmail() {
        ProfileChangeRequest request = persistRequest(ana, ProfileField.EMAIL, newEmail);
        given(keycloak.usersWithEmail(newEmail)).willReturn(List.of(account(admin, "Setor 1")));

        assertThatThrownBy(() -> profiles.approve(request.getId(), admin.getId()))
                .isInstanceOf(UserExistsException.class);
        verify(keycloak, never()).updateUser(anyString(), any(), any());
        assertThat(requests.findById(request.getId()).orElseThrow().isPending()).isTrue();
    }

    @Test
    @DisplayName("approving a CPF sets the attribute, the rest kept")
    void approvesCpf() {
        ProfileChangeRequest request = persistRequest(ana, ProfileField.CPF, NEW_CPF);

        profiles.approve(request.getId(), admin.getId());

        Map<String, Object> sent = sentToKeycloak(ana.getKeycloakId(), null);
        assertThat(sent.get("attributes")).isEqualTo(Map.of("cpf", List.of(NEW_CPF), "sector", List.of("Setor 1")));
        assertThat(sent).doesNotContainKey("email");
    }

    @Test
    @DisplayName("rejecting keeps the note, changes nothing in Keycloak, and is audited without values")
    void rejects() {
        ProfileChangeRequest request = persistRequest(ana, ProfileField.CPF, NEW_CPF);

        ChangeRequest rejected = profiles.reject(request.getId(), admin.getId(), "  CPF de outra pessoa  ");

        assertThat(rejected.status()).isEqualTo(ChangeRequestStatus.REJECTED);
        assertThat(rejected.decisionNote()).isEqualTo("CPF de outra pessoa");
        verify(keycloak, never()).updateUser(anyString(), any(), any());
        assertThat(events(AuditService.PROFILE_CHANGE_REJECTED)).singleElement()
                .satisfies(entry -> assertThat(details(entry))
                        .isEqualTo(Map.of("field", "cpf", "target_keycloak_id", ana.getKeycloakId())));
        assertThatThrownBy(() -> profiles.reject(request.getId(), admin.getId(), null))
                .isInstanceOf(ChangeRequestNotPendingException.class);
        assertThatThrownBy(() -> profiles.reject(-1L, admin.getId(), null))
                .isInstanceOf(ChangeRequestNotFoundException.class);
    }

    @Test
    @DisplayName("the admin's list filters by status, PENDING by default, newest first, with who asked")
    void lists() {
        ProfileChangeRequest pending = persistRequest(ana, ProfileField.EMAIL, newEmail);
        ProfileChangeRequest decided = persistRequest(ana, ProfileField.CPF, NEW_CPF);
        decided.decide(ChangeRequestStatus.APPROVED, admin, null, null);
        entityManager.flush();

        PageResponse<ChangeRequest> page = profiles.list(null, 0, 100);
        assertThat(page.content()).extracting(ChangeRequest::id).contains(pending.getId())
                .doesNotContain(decided.getId());
        assertThat(page.content()).filteredOn(item -> item.id().equals(pending.getId())).singleElement()
                .satisfies(item -> assertThat(item.user().name()).isEqualTo("Ana Souza"));
        assertThat(profiles.list(ChangeRequestStatusFilter.APPROVED, 0, 100).content()).extracting(ChangeRequest::id)
                .contains(decided.getId()).doesNotContain(pending.getId());
        assertThat(profiles.list(ChangeRequestStatusFilter.ALL, 0, 100).content()).extracting(ChangeRequest::id)
                .contains(pending.getId(), decided.getId());
        assertThatThrownBy(() -> profiles.list(null, 0, 101)).isInstanceOf(InvalidPageRequestException.class);
    }

    @Test
    @DisplayName("the admin edits every field: e-mail verified, row follows, USER_UPDATED names the fields only")
    void adminEdits() {
        profiles.updateAsAdmin(ana.getKeycloakId(),
                new AdminProfileUpdateRequest("Ana", "Souza", newEmail, "987.654.321-00", "Setor 1"));

        Map<String, Object> sent = sentToKeycloak(ana.getKeycloakId(), newEmail);
        assertThat(sent).containsEntry("email", newEmail).containsEntry("emailVerified", true);
        assertThat(sent.get("attributes")).isEqualTo(Map.of("cpf", List.of(NEW_CPF), "sector", List.of("Setor 1")));
        entityManager.flush();
        assertThat(users.findById(ana.getId()).orElseThrow().getEmail()).isEqualTo(newEmail);
        assertThat(events(AuditService.USER_UPDATED)).singleElement().satisfies(entry -> {
            assertThat(details(entry)).isEqualTo(Map.of("target_keycloak_id", ana.getKeycloakId(),
                    "fields", List.of("email", "cpf")));
            assertThat(entry.getEntityId()).isEqualTo(ana.getId());
        });
    }

    @Test
    @DisplayName("the admin's edit: invalid_profile, nothing_changed, and user_exists for another account's e-mail")
    void adminEditRefusals() {
        String id = ana.getKeycloakId();
        assertThatThrownBy(() -> profiles.updateAsAdmin(id,
                new AdminProfileUpdateRequest("Ana", "Souza", ana.getEmail(), CPF, "Setor 1")))
                .isInstanceOf(NothingChangedException.class);
        assertThatThrownBy(() -> profiles.updateAsAdmin(id,
                new AdminProfileUpdateRequest("Ana", "Souza", ana.getEmail(), "1", "Setor 1")))
                .isInstanceOf(InvalidProfileException.class);
        // Another row has it, even if Keycloak does not know it.
        assertThatThrownBy(() -> profiles.updateAsAdmin(id,
                new AdminProfileUpdateRequest("Ana", "Souza", admin.getEmail(), CPF, "Setor 1")))
                .isInstanceOf(UserExistsException.class);
        given(keycloak.usersWithEmail(newEmail)).willReturn(List.of(account(admin, "Setor 1")));
        assertThatThrownBy(() -> profiles.updateAsAdmin(id,
                new AdminProfileUpdateRequest("Ana", "Souza", newEmail, CPF, "Setor 1")))
                .isInstanceOf(UserExistsException.class);
        verify(keycloak, never()).updateUser(anyString(), any(), any());
    }
}
