package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.akpedia.server.client.KeycloakAdminClient;
import com.akpedia.server.client.KeycloakRole;
import com.akpedia.server.client.KeycloakUser;
import com.akpedia.server.dto.AdminUser;
import com.akpedia.server.dto.CreateUserRequest;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.TermsVersion;
import com.akpedia.server.entity.User;
import com.akpedia.server.exception.CannotChangeOwnAdminException;
import com.akpedia.server.exception.CannotDisableSelfException;
import com.akpedia.server.exception.CpfTakenException;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.exception.InvalidRoleException;
import com.akpedia.server.exception.InvalidUserException;
import com.akpedia.server.exception.KeycloakUnavailableException;
import com.akpedia.server.exception.LastAdminException;
import com.akpedia.server.exception.UserExistsException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.TermsAcceptanceRepository;
import com.akpedia.server.repository.TermsVersionRepository;
import com.akpedia.server.repository.UserRepository;

/** The users screen's rules, with Keycloak mocked: what is added, removed and recorded, and what is refused. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminUserServiceTest {

    private static final String ADMIN_ID = "admin-0000";
    private static final String ANA_ID = "ana-1111";

    private static final KeycloakRole STANDARD = new KeycloakRole("r-std", "STANDARD");
    private static final KeycloakRole APPROVER = new KeycloakRole("r-app", "APPROVER");
    private static final KeycloakRole ADMIN = new KeycloakRole("r-adm", "ADMIN");
    private static final KeycloakRole DEFAULT_ROLES = new KeycloakRole("r-def", "default-roles-akpedia");

    @Mock
    private KeycloakAdminClient keycloak;
    @Mock
    private TermsVersionRepository termsVersions;
    @Mock
    private TermsAcceptanceRepository termsAcceptances;
    @Mock
    private UserRepository users;
    @Mock
    private AuditService audit;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(keycloak, termsVersions, termsAcceptances, users, audit);
        given(keycloak.user(ANA_ID)).willReturn(user(ANA_ID, "ana@akpedia.test", true));
        given(keycloak.user(ADMIN_ID)).willReturn(user(ADMIN_ID, "admin@akpedia.test", true));
        given(termsVersions.findFirstByOrderByPublishedAtDescIdDesc()).willReturn(Optional.empty());
        given(users.findIdByKeycloakId(ANA_ID)).willReturn(Optional.of(42L));
    }

    private static KeycloakUser user(String id, String email, boolean enabled) {
        return new KeycloakUser(id, email, email, "Ana", "Souza", enabled, 1_762_000_000_000L,
                Map.of("sector", List.of("Engenharia")));
    }

    /** Ana's direct mappings before the change, and her effective roles before and after it. */
    @SuppressWarnings("unchecked")
    private void anaHas(List<KeycloakRole> direct, List<KeycloakRole> effectiveBefore, List<KeycloakRole> effectiveAfter) {
        given(keycloak.realmRoles(ANA_ID)).willReturn(direct);
        given(keycloak.effectiveRealmRoles(ANA_ID)).willReturn(effectiveBefore, effectiveAfter);
        given(keycloak.availableRealmRoles(ANA_ID)).willReturn(
                List.of(APPROVER, ADMIN).stream().filter(role -> !direct.contains(role)).toList());
    }

    @Test
    @DisplayName("roles: the missing ones are added, the others removed, and the change is recorded")
    void setRolesAddsAndRemoves() {
        anaHas(List.of(DEFAULT_ROLES, APPROVER), List.of(STANDARD, APPROVER), List.of(STANDARD, ADMIN));

        AdminUser result = service.setRoles(ANA_ID, List.of("ADMIN"), ADMIN_ID);

        verify(keycloak).removeRealmRoles(ANA_ID, List.of(APPROVER));
        verify(keycloak).addRealmRoles(ANA_ID, List.of(ADMIN));
        assertThat(result.roles()).containsExactly("STANDARD", "ADMIN");
        assertThat(result.sector()).isEqualTo("Engenharia");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(null), eq("USER_ROLES_CHANGED"), eq("USER"), eq(42L), details.capture());
        assertThat(details.getValue())
                .containsEntry("target_keycloak_id", ANA_ID)
                .containsEntry("target_email", "ana@akpedia.test")
                .containsEntry("before", List.of("STANDARD", "APPROVER"))
                .containsEntry("after", List.of("STANDARD", "ADMIN"));
    }

    @Test
    @DisplayName("roles: STANDARD and the default composite are never removed")
    void setRolesKeepsStandard() {
        anaHas(List.of(DEFAULT_ROLES, APPROVER), List.of(STANDARD, APPROVER), List.of(STANDARD));

        service.setRoles(ANA_ID, List.of(), ADMIN_ID);

        verify(keycloak).removeRealmRoles(ANA_ID, List.of(APPROVER));
        verify(keycloak, never()).addRealmRoles(anyString(), any());
    }

    @Test
    @DisplayName("roles: asking for what the user already has changes and records nothing")
    void setRolesWithoutChange() {
        anaHas(List.of(DEFAULT_ROLES, APPROVER), List.of(STANDARD, APPROVER), List.of(STANDARD, APPROVER));

        AdminUser result = service.setRoles(ANA_ID, List.of("APPROVER"), ADMIN_ID);

        assertThat(result.roles()).containsExactly("STANDARD", "APPROVER");
        verify(keycloak, never()).removeRealmRoles(anyString(), any());
        verify(keycloak, never()).addRealmRoles(anyString(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("roles: STANDARD or an unknown role is invalid_role, before anything is changed")
    void setRolesRefusesUnassignable() {
        assertThatThrownBy(() -> service.setRoles(ANA_ID, List.of("STANDARD"), ADMIN_ID))
                .isInstanceOf(InvalidRoleException.class);
        assertThatThrownBy(() -> service.setRoles(ANA_ID, List.of("APPROVER", "ROOT"), ADMIN_ID))
                .isInstanceOf(InvalidRoleException.class);
        verify(keycloak, never()).user(anyString());
    }

    @Test
    @DisplayName("roles: an admin cannot take ADMIN from themself, but can keep it while changing the rest")
    void setRolesProtectsOwnAdmin() {
        assertThatThrownBy(() -> service.setRoles(ADMIN_ID, List.of("APPROVER"), ADMIN_ID))
                .isInstanceOf(CannotChangeOwnAdminException.class);
        verify(keycloak, never()).removeRealmRoles(anyString(), any());

        given(keycloak.realmRoles(ADMIN_ID)).willReturn(List.of(ADMIN));
        given(keycloak.effectiveRealmRoles(ADMIN_ID)).willReturn(List.of(STANDARD, ADMIN));
        given(keycloak.availableRealmRoles(ADMIN_ID)).willReturn(List.of(APPROVER));

        service.setRoles(ADMIN_ID, List.of("ADMIN", "APPROVER"), ADMIN_ID);

        verify(keycloak).addRealmRoles(ADMIN_ID, List.of(APPROVER));
    }

    @Test
    @DisplayName("an unknown user is user_not_found, and Keycloak failures pass through")
    void unknownUserAndKeycloakFailures() {
        given(keycloak.user("nope")).willThrow(new UserNotFoundException("nope"));
        given(keycloak.user("down")).willThrow(new KeycloakUnavailableException("ler o usuario", null));

        assertThatThrownBy(() -> service.setRoles("nope", List.of("ADMIN"), ADMIN_ID))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.setEnabled("nope", false, ADMIN_ID))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.setEnabled("down", true, ADMIN_ID))
                .isInstanceOf(KeycloakUnavailableException.class);
    }

    @Test
    @DisplayName("status: disabling is done in Keycloak, mirrored on the user's row and recorded")
    void disableMirrorsAndRecords() {
        User row = new User("Ana Souza", "ana@akpedia.test", new Sector("Engenharia", null));
        given(users.findByKeycloakId(ANA_ID)).willReturn(Optional.of(row));
        given(keycloak.effectiveRealmRoles(ANA_ID)).willReturn(List.of(STANDARD));

        AdminUser result = service.setEnabled(ANA_ID, false, ADMIN_ID);

        verify(keycloak).setEnabled(ANA_ID, false);
        verify(keycloak).logout(ANA_ID);
        assertThat(row.isActive()).isFalse();
        assertThat(result.enabled()).isFalse();
        verify(audit).record(eq(null), eq("USER_DISABLED"), eq("USER"), eq(42L), any());
    }

    @Test
    @DisplayName("status: an account already so is left alone, and nothing is recorded")
    void statusUnchanged() {
        service.setEnabled(ANA_ID, true, ADMIN_ID);

        verify(keycloak, never()).setEnabled(anyString(), anyBoolean());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("status: an admin cannot disable themself, but enabling themself is harmless")
    void cannotDisableSelf() {
        assertThatThrownBy(() -> service.setEnabled(ADMIN_ID, false, ADMIN_ID))
                .isInstanceOf(CannotDisableSelfException.class);
        verify(keycloak, never()).setEnabled(anyString(), anyBoolean());

        service.setEnabled(ADMIN_ID, true, ADMIN_ID);
    }

    @Test
    @DisplayName("list: pages through Keycloak, with each user's roles and terms status")
    void listPagesWithRolesAndTerms() {
        TermsVersion current = mock(TermsVersion.class);
        given(current.getVersion()).willReturn("3.0");
        given(termsVersions.findFirstByOrderByPublishedAtDescIdDesc()).willReturn(Optional.of(current));
        given(keycloak.users("ana", 20, 10)).willReturn(List.of(user(ANA_ID, "ana@akpedia.test", true)));
        given(keycloak.countUsers("ana")).willReturn(21L);
        given(keycloak.effectiveRealmRoles(ANA_ID)).willReturn(List.of(DEFAULT_ROLES, STANDARD, APPROVER));
        given(termsAcceptances.findLastAcceptedVersions(List.of(ANA_ID)))
                .willReturn(List.<Object[]>of(new Object[] {ANA_ID, "2.0"}));

        PageResponse<AdminUser> page = service.list("ana", 2, 10);

        assertThat(page.totalElements()).isEqualTo(21);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.content()).singleElement().satisfies(user -> {
            assertThat(user.name()).isEqualTo("Ana Souza");
            assertThat(user.roles()).containsExactly("STANDARD", "APPROVER");
            assertThat(user.termsAcceptedVersion()).isEqualTo("2.0");
            assertThat(user.currentTermsVersion()).isEqualTo("3.0");
            assertThat(user.createdAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("the only enabled admin cannot be disabled nor lose ADMIN, even by an admin whose token is stale")
    void lastAdminIsProtected() {
        given(keycloak.effectiveRealmRoles(ANA_ID)).willReturn(List.of(STANDARD, ADMIN));
        given(keycloak.realmRoles(ANA_ID)).willReturn(List.of(DEFAULT_ROLES, ADMIN));
        // The caller lost ADMIN in Keycloak a minute ago; their token still has it.
        given(keycloak.effectiveRealmRoles(ADMIN_ID)).willReturn(List.of(STANDARD));
        given(keycloak.users(null, 0, AdminUserService.ADMIN_SEARCH_PAGE)).willReturn(List.of(
                user(ANA_ID, "ana@akpedia.test", true), user(ADMIN_ID, "admin@akpedia.test", true)));

        assertThatThrownBy(() -> service.setEnabled(ANA_ID, false, ADMIN_ID)).isInstanceOf(LastAdminException.class);
        assertThatThrownBy(() -> service.setRoles(ANA_ID, List.of("APPROVER"), ADMIN_ID))
                .isInstanceOf(LastAdminException.class);
        verify(keycloak, never()).setEnabled(anyString(), anyBoolean());
        verify(keycloak, never()).removeRealmRoles(anyString(), any());
    }

    @Test
    @DisplayName("an admin can be disabled while another enabled admin remains")
    void adminWithAnotherAdminCanBeDisabled() {
        given(keycloak.effectiveRealmRoles(ANA_ID)).willReturn(List.of(STANDARD, ADMIN));
        given(keycloak.effectiveRealmRoles(ADMIN_ID)).willReturn(List.of(STANDARD, ADMIN));
        given(keycloak.users(null, 0, AdminUserService.ADMIN_SEARCH_PAGE)).willReturn(List.of(
                user(ANA_ID, "ana@akpedia.test", true), user(ADMIN_ID, "admin@akpedia.test", true)));

        service.setEnabled(ANA_ID, false, ADMIN_ID);

        verify(keycloak).setEnabled(ANA_ID, false);
    }

    @Test
    @DisplayName("list: a role filter reads the whole realm and keeps the users with it; STANDARD means neither")
    void listFiltersByRole() {
        KeycloakUser bia = user("bia-2222", "bia@akpedia.test", true);
        given(keycloak.users("souza", null, 0, AdminUserService.ADMIN_SEARCH_PAGE))
                .willReturn(List.of(user(ANA_ID, "ana@akpedia.test", true), bia));
        given(keycloak.effectiveRealmRoles(ANA_ID)).willReturn(List.of(STANDARD, APPROVER));
        given(keycloak.effectiveRealmRoles("bia-2222")).willReturn(List.of(DEFAULT_ROLES, STANDARD));

        PageResponse<AdminUser> approvers = service.list("souza", null, "approver", 0, 20);
        assertThat(approvers.content()).extracting(AdminUser::id).containsExactly(ANA_ID);
        assertThat(approvers.totalElements()).isEqualTo(1);

        PageResponse<AdminUser> standard = service.list("souza", null, "STANDARD", 0, 20);
        assertThat(standard.content()).extracting(AdminUser::id).containsExactly("bia-2222");

        assertThatThrownBy(() -> service.list(null, null, "ROOT", 0, 20)).isInstanceOf(InvalidRoleException.class);
    }

    @Test
    @DisplayName("list: a sector filter asks Keycloak for the sector and searches here, paging the result")
    void listFiltersBySector() {
        KeycloakUser bia = new KeycloakUser("bia-2222", "bia@akpedia.test", "bia@akpedia.test", "Bia", "Lima", true,
                null, Map.of("sector", List.of("Qualidade")));
        KeycloakUser carla = new KeycloakUser("carla-3333", "carla@akpedia.test", "carla@akpedia.test", "Carla",
                "Lima", true, null, Map.of("sector", List.of("Qualidade")));
        given(keycloak.users(null, "Qualidade", 0, AdminUserService.ADMIN_SEARCH_PAGE))
                .willReturn(List.of(bia, carla));
        given(keycloak.effectiveRealmRoles(anyString())).willReturn(List.of(STANDARD));

        assertThat(service.list("LIMA", "Qualidade", null, 1, 1).content()).extracting(AdminUser::id)
                .containsExactly("carla-3333");
        assertThat(service.list("carla", "Qualidade", null, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("create: a CPF with wrong check digits, or another user's, is refused before Keycloak is touched")
    void createRefusesBadOrTakenCpf() {
        assertThatThrownBy(() -> service.create(newUser("123.456.789-01", "Logística", List.of())))
                .isInstanceOf(InvalidUserException.class).hasMessageContaining("CPF inválido");

        given(keycloak.usersWithCpf("12345678909")).willReturn(List.of(user(ANA_ID, "ana@akpedia.test", true)));
        assertThatThrownBy(() -> service.create(newUser("123.456.789-09", "Logística", List.of())))
                .isInstanceOf(CpfTakenException.class);
        verify(keycloak, never()).createUser(any());
    }

    @Test
    @DisplayName("list: a negative page or a size out of range is refused")
    void listValidatesThePage() {
        assertThatThrownBy(() -> service.list(null, -1, null)).isInstanceOf(InvalidPageRequestException.class);
        assertThatThrownBy(() -> service.list(null, 0, 101)).isInstanceOf(InvalidPageRequestException.class);
    }

    private static CreateUserRequest newUser(String cpf, String sector, List<String> roles) {
        return new CreateUserRequest(" Bia ", "Lima", " Bia@Akpedia.test ", "s3gr3d0!", cpf, sector, roles);
    }

    @Test
    @DisplayName("create: the account, its password and roles in Keycloak, recorded without the password")
    void createsUser() {
        given(keycloak.createUser(any())).willReturn("bia-2222");
        given(keycloak.availableRealmRoles("bia-2222")).willReturn(List.of(APPROVER, ADMIN));
        given(keycloak.effectiveRealmRoles("bia-2222")).willReturn(List.of(DEFAULT_ROLES, STANDARD, APPROVER));
        given(keycloak.user("bia-2222")).willReturn(new KeycloakUser("bia-2222", "bia@akpedia.test",
                "bia@akpedia.test", "Bia", "Lima", true, 1_762_000_000_000L, Map.of("sector", List.of("Logística"))));

        AdminUser created = service.create(newUser("123.456.789-09", "Logística", List.of("APPROVER")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> representation = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).createUser(representation.capture());
        assertThat(representation.getValue())
                .containsEntry("username", "bia@akpedia.test")
                .containsEntry("email", "bia@akpedia.test")
                .containsEntry("firstName", "Bia")
                .containsEntry("lastName", "Lima")
                .containsEntry("enabled", true)
                .containsEntry("emailVerified", true)
                .containsEntry("attributes", Map.of("cpf", List.of("12345678909"), "sector", List.of("Logística")))
                .doesNotContainKey("credentials");
        verify(keycloak).setPassword("bia-2222", "s3gr3d0!");
        verify(keycloak).addRealmRoles("bia-2222", List.of(APPROVER));
        assertThat(created.id()).isEqualTo("bia-2222");
        assertThat(created.roles()).containsExactly("STANDARD", "APPROVER");
        assertThat(created.sector()).isEqualTo("Logística");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(null), eq("USER_CREATED"), eq("USER"), eq(null), details.capture());
        assertThat(details.getValue())
                .containsEntry("target_keycloak_id", "bia-2222")
                .containsEntry("target_email", "bia@akpedia.test")
                .containsEntry("roles", List.of("APPROVER"));
        assertThat(details.getValue().toString()).doesNotContain("s3gr3d0!");
        assertThat(newUser("1", "x", List.of()).toString()).doesNotContain("s3gr3d0!");
    }

    @Test
    @DisplayName("create: a step failing after the account exists deletes it again")
    void createRollsBack() {
        given(keycloak.createUser(any())).willReturn("bia-2222");
        willThrow(new InvalidUserException("senha fraca")).given(keycloak).setPassword("bia-2222", "s3gr3d0!");

        assertThatThrownBy(() -> service.create(newUser("12345678909", "Engenharia", List.of())))
                .isInstanceOf(InvalidUserException.class);

        verify(keycloak).deleteUser("bia-2222");
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("create: each missing or invalid field is invalid_user, before Keycloak is called")
    void createValidates() {
        List<CreateUserRequest> invalid = List.of(
                new CreateUserRequest(null, "Lima", "bia@akpedia.test", "x", "12345678909", "Engenharia", List.of()),
                new CreateUserRequest("Bia", " ", "bia@akpedia.test", "x", "12345678909", "Engenharia", List.of()),
                new CreateUserRequest("Bia", "Lima", "bia", "x", "12345678909", "Engenharia", List.of()),
                new CreateUserRequest("Bia", "Lima", "bia@akpedia.test", "", "12345678909", "Engenharia", List.of()),
                new CreateUserRequest("Bia", "Lima", "bia@akpedia.test", "x", "1234567890", "Engenharia", List.of()),
                new CreateUserRequest("Bia", "Lima", "bia@akpedia.test", "x", "1234567890a", "Engenharia", List.of()),
                new CreateUserRequest("Bia", "Lima", "bia@akpedia.test", "x", "12345678909", "Setor 9", List.of()),
                new CreateUserRequest("Bia", "Lima", "bia@akpedia.test", "x", "12345678909", "Engenharia",
                        List.of("STANDARD")));

        for (CreateUserRequest request : invalid) {
            assertThatThrownBy(() -> service.create(request)).isInstanceOf(InvalidUserException.class);
        }
        verify(keycloak, never()).createUser(any());
    }

    @Test
    @DisplayName("create: an email already taken is user_exists, and nothing else happens")
    void createExisting() {
        given(keycloak.createUser(any())).willThrow(new UserExistsException("bia@akpedia.test"));

        assertThatThrownBy(() -> service.create(newUser("12345678909", "Engenharia", List.of())))
                .isInstanceOf(UserExistsException.class);
        verify(keycloak, never()).setPassword(anyString(), anyString());
        verify(keycloak, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("the role catalog offers only APPROVER and ADMIN to assign")
    void roleCatalog() {
        assertThat(service.roles()).extracting(role -> role.name() + ":" + role.assignable())
                .containsExactly("STANDARD:false", "APPROVER:true", "ADMIN:true");
    }
}
