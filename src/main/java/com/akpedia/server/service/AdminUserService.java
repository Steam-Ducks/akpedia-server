package com.akpedia.server.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.client.KeycloakAdminClient;
import com.akpedia.server.client.KeycloakRole;
import com.akpedia.server.client.KeycloakUser;
import com.akpedia.server.dto.AdminRole;
import com.akpedia.server.dto.AdminUser;
import com.akpedia.server.dto.CreateUserRequest;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.entity.TermsVersion;
import com.akpedia.server.exception.CannotChangeOwnAdminException;
import com.akpedia.server.exception.CannotDisableSelfException;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.exception.InvalidRoleException;
import com.akpedia.server.exception.InvalidUserException;
import com.akpedia.server.exception.LastAdminException;
import com.akpedia.server.exception.UserExistsException;
import com.akpedia.server.repository.TermsAcceptanceRepository;
import com.akpedia.server.repository.TermsVersionRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * The admin's users screen: the realm's users with their roles and terms status, giving and
 * taking the APPROVER and ADMIN roles, and enabling or disabling accounts.
 *
 * <p>Users and roles live in Keycloak, so this reads and changes them there, through the Admin
 * API. A change reaches the user's token on its next refresh, not at once.
 *
 * <p>An admin cannot take ADMIN from themself nor disable their own account, and nobody can take
 * ADMIN from, or disable, the only enabled admin: either could leave the realm with nobody to undo
 * it. A disabled account's sessions are ended at once.
 */
@Service
public class AdminUserService {

    public static final String STANDARD = "STANDARD";
    public static final String APPROVER = "APPROVER";
    public static final String ADMIN = "ADMIN";

    /** The roles the screen knows, in the order it shows them; any other realm role is left out. */
    static final List<String> KNOWN_ROLES = List.of(STANDARD, APPROVER, ADMIN);
    static final Set<String> ASSIGNABLE_ROLES = Set.of(APPROVER, ADMIN);

    static final List<AdminRole> ROLES = List.of(
            new AdminRole(STANDARD, "Padrão", "Todo usuário cadastrado. Vem com a conta e não é removido.", false,
                    List.of("Consultar e buscar documentos", "Enviar documentos")),
            new AdminRole(APPROVER, "Aprovador", "Revisa os documentos enviados antes de entrarem na busca.", true,
                    List.of("Revisar a fila de pendentes", "Aprovar ou devolver documentos")),
            new AdminRole(ADMIN, "Administrador", "Administra o Akpedia, e também revisa documentos.", true,
                    List.of("Gerenciar usuários e papéis", "Publicar termos de uso", "Consultar a auditoria")));

    /**
     * The sectors the realm's user profile offers (keycloak/akpedia-realm.json, attribute
     * {@code sector}) and migration V13 seeds. Keep all three in step.
     */
    public static final List<String> SECTORS = List.of("Engenharia", "Qualidade", "Logística", "Manutenção",
            "Recursos Humanos", "Financeiro", "Jurídico");

    static final int DEFAULT_PAGE_SIZE = 20;
    /** Page size when scanning the whole realm for its admins. */
    static final int ADMIN_SEARCH_PAGE = 100;
    static final int MAX_PAGE_SIZE = 100;

    private final KeycloakAdminClient keycloak;
    private final TermsVersionRepository termsVersions;
    private final TermsAcceptanceRepository termsAcceptances;
    private final UserRepository users;
    private final AuditService audit;

    public AdminUserService(KeycloakAdminClient keycloak, TermsVersionRepository termsVersions,
            TermsAcceptanceRepository termsAcceptances, UserRepository users, AuditService audit) {
        this.keycloak = keycloak;
        this.termsVersions = termsVersions;
        this.termsAcceptances = termsAcceptances;
        this.users = users;
        this.audit = audit;
    }

    /** The roles and what each allows. */
    public List<AdminRole> roles() {
        return ROLES;
    }

    /**
     * One page of the realm's users, in Keycloak's order (by username, which is the email).
     *
     * @param search text looked for in the email and name
     * @throws InvalidPageRequestException if the page is negative or the size out of range
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUser> list(String search, Integer requestedPage, Integer requestedSize) {
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPageRequestException(MAX_PAGE_SIZE);
        }
        List<KeycloakUser> found = keycloak.users(search, page * size, size);
        long total = keycloak.countUsers(search);

        String current = currentTermsVersion();
        Map<String, String> accepted = lastAcceptedVersions(found.stream().map(KeycloakUser::id).toList());
        List<AdminUser> content = found.stream()
                .map(user -> toAdminUser(user, user.isEnabled(), roleNames(keycloak.effectiveRealmRoles(user.id())),
                        accepted.get(user.id()), current))
                .toList();
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    /**
     * One user, as the list shows them.
     *
     * @throws com.akpedia.server.exception.UserNotFoundException if there is no such user
     */
    @Transactional(readOnly = true)
    public AdminUser get(String id) {
        KeycloakUser user = keycloak.user(id);
        return toAdminUser(user, user.isEnabled(), roleNames(keycloak.effectiveRealmRoles(id)), lastAcceptedVersion(id),
                currentTermsVersion());
    }

    /**
     * Gives the user exactly these assignable roles: the missing ones are added, the others
     * removed. STANDARD and any role outside the screen are left as they are.
     *
     * @param id       the user's Keycloak id
     * @param roles    APPROVER, ADMIN, both or neither
     * @param callerId the admin's own Keycloak id
     * @throws InvalidRoleException          if a role is not APPROVER or ADMIN
     * @throws CannotChangeOwnAdminException if the admin would lose ADMIN themself
     * @throws LastAdminException            if the user is the only enabled admin and would lose ADMIN
     * @throws com.akpedia.server.exception.UserNotFoundException if there is no such user
     */
    @Transactional
    public AdminUser setRoles(String id, Collection<String> roles, String callerId) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String role : roles) {
            if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
                throw new InvalidRoleException(String.valueOf(role));
            }
            wanted.add(role);
        }
        KeycloakUser user = keycloak.user(id);
        if (id.equals(callerId) && !wanted.contains(ADMIN)) {
            throw new CannotChangeOwnAdminException();
        }

        List<String> before = roleNames(keycloak.effectiveRealmRoles(id));
        if (before.contains(ADMIN) && !wanted.contains(ADMIN) && user.isEnabled()
                && !hasOtherEnabledAdmin(keycloak, id)) {
            throw LastAdminException.ofOther();
        }
        List<KeycloakRole> direct = keycloak.realmRoles(id);
        Set<String> directNames = direct.stream().map(KeycloakRole::name).collect(Collectors.toSet());

        List<KeycloakRole> toRemove = direct.stream()
                .filter(role -> ASSIGNABLE_ROLES.contains(role.name()) && !wanted.contains(role.name()))
                .toList();
        Set<String> missing = wanted.stream().filter(role -> !directNames.contains(role)).collect(Collectors.toSet());
        // Taken from the roles the user can still get: the service account cannot look roles up by
        // name. A wanted role not offered there the user already has some other way.
        List<KeycloakRole> toAdd = missing.isEmpty()
                ? List.of()
                : keycloak.availableRealmRoles(id).stream().filter(role -> missing.contains(role.name())).toList();

        if (!toRemove.isEmpty()) {
            keycloak.removeRealmRoles(id, toRemove);
        }
        if (!toAdd.isEmpty()) {
            keycloak.addRealmRoles(id, toAdd);
        }
        List<String> after = toRemove.isEmpty() && toAdd.isEmpty()
                ? before
                : roleNames(keycloak.effectiveRealmRoles(id));
        if (!toRemove.isEmpty() || !toAdd.isEmpty()) {
            audit.record(null, AuditService.USER_ROLES_CHANGED, AuditService.USER, localId(id),
                    AuditService.details("target_keycloak_id", id, "target_email", user.email(),
                            "before", before, "after", after));
        }
        return toAdminUser(user, user.isEnabled(), after, lastAcceptedVersion(id), currentTermsVersion());
    }

    /**
     * Enables or disables the account, and mirrors it on the user's row, when there is one. A
     * disabled account's sessions are ended, so its token cannot be refreshed; until the token
     * expires, the row's {@code is_active} keeps it out (see
     * {@link com.akpedia.server.security.DisabledAccountFilter}).
     *
     * @param callerId the admin's own Keycloak id
     * @throws CannotDisableSelfException if the admin would disable themself
     * @throws LastAdminException         if the user is the only enabled admin
     * @throws com.akpedia.server.exception.UserNotFoundException if there is no such user
     */
    @Transactional
    public AdminUser setEnabled(String id, boolean enabled, String callerId) {
        if (id.equals(callerId) && !enabled) {
            throw new CannotDisableSelfException();
        }
        KeycloakUser user = keycloak.user(id);
        if (user.isEnabled() != enabled) {
            List<String> roles = roleNames(keycloak.effectiveRealmRoles(id));
            if (!enabled && roles.contains(ADMIN) && !hasOtherEnabledAdmin(keycloak, id)) {
                throw LastAdminException.ofOther();
            }
            keycloak.setEnabled(id, enabled);
            if (!enabled) {
                keycloak.logout(id);
            }
            users.findByKeycloakId(id).ifPresent(row -> row.setActive(enabled));
            audit.record(null, enabled ? AuditService.USER_ENABLED : AuditService.USER_DISABLED, AuditService.USER,
                    localId(id), AuditService.details("target_keycloak_id", id, "target_email", user.email()));
        }
        return toAdminUser(user, enabled, roleNames(keycloak.effectiveRealmRoles(id)), lastAcceptedVersion(id),
                currentTermsVersion());
    }

    /**
     * Creates an account in Keycloak, with its password and roles, as if the user had registered.
     * If a step after the account was created fails, the half-made account is deleted again.
     * The password is never logged nor recorded.
     *
     * @throws InvalidUserException if a field is missing or invalid, or Keycloak refused one
     * @throws UserExistsException  if the realm already has a user with that email
     */
    @Transactional
    public AdminUser create(CreateUserRequest request) {
        NewUser user = validate(request);
        Map<String, Object> representation = new LinkedHashMap<>();
        representation.put("username", user.email());
        representation.put("email", user.email());
        representation.put("firstName", user.firstName());
        representation.put("lastName", user.lastName());
        representation.put("enabled", true);
        representation.put("emailVerified", true);
        representation.put("attributes", Map.of("cpf", List.of(user.cpf()), "sector", List.of(user.sector())));
        String id = keycloak.createUser(representation);
        try {
            keycloak.setPassword(id, request.password());
            if (!user.roles().isEmpty()) {
                List<KeycloakRole> toAdd = keycloak.availableRealmRoles(id).stream()
                        .filter(role -> user.roles().contains(role.name()))
                        .toList();
                keycloak.addRealmRoles(id, toAdd);
            }
            List<String> roles = roleNames(keycloak.effectiveRealmRoles(id));
            audit.record(null, AuditService.USER_CREATED, AuditService.USER, null, AuditService.details(
                    "target_keycloak_id", id, "target_email", user.email(), "roles", List.copyOf(user.roles())));
            return toAdminUser(keycloak.user(id), true, roles, null, currentTermsVersion());
        } catch (RuntimeException e) {
            try {
                keycloak.deleteUser(id);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /** The request's fields, checked and cleaned. */
    private record NewUser(String firstName, String lastName, String email, String cpf, String sector,
            Set<String> roles) {
    }

    private static NewUser validate(CreateUserRequest request) {
        String firstName = ProfileRules.required(request.firstName(), "o nome", InvalidUserException::new);
        String lastName = ProfileRules.required(request.lastName(), "o sobrenome", InvalidUserException::new);
        String email = ProfileRules.email(request.email(), InvalidUserException::new);
        if (request.password() == null || request.password().isEmpty()) {
            throw new InvalidUserException("Informe a senha.");
        }
        String cpf = ProfileRules.cpf(request.cpf(), InvalidUserException::new);
        String sector = ProfileRules.sector(request.sector(), InvalidUserException::new);
        Set<String> roles = new LinkedHashSet<>();
        for (String role : request.roles() == null ? List.<String>of() : request.roles()) {
            if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
                throw new InvalidUserException("Papel inválido: %s. Os papéis possíveis são APPROVER e ADMIN."
                        .formatted(role));
            }
            roles.add(role);
        }
        return new NewUser(firstName, lastName, email, cpf, sector, roles);
    }

    /** Whether an enabled user other than this one has ADMIN, looked for across the whole realm. */
    static boolean hasOtherEnabledAdmin(KeycloakAdminClient keycloak, String keycloakId) {
        for (int first = 0;; first += ADMIN_SEARCH_PAGE) {
            List<KeycloakUser> page = keycloak.users(null, first, ADMIN_SEARCH_PAGE);
            for (KeycloakUser user : page) {
                if (!user.id().equals(keycloakId) && user.isEnabled()
                        && roleNames(keycloak.effectiveRealmRoles(user.id())).contains(ADMIN)) {
                    return true;
                }
            }
            if (page.size() < ADMIN_SEARCH_PAGE) {
                return false;
            }
        }
    }

    private Long localId(String keycloakId) {
        return users.findIdByKeycloakId(keycloakId).orElse(null);
    }

    private String currentTermsVersion() {
        return termsVersions.findFirstByOrderByPublishedAtDescIdDesc().map(TermsVersion::getVersion).orElse(null);
    }

    private String lastAcceptedVersion(String keycloakId) {
        return lastAcceptedVersions(List.of(keycloakId)).get(keycloakId);
    }

    private Map<String, String> lastAcceptedVersions(List<String> keycloakIds) {
        Map<String, String> versions = new HashMap<>();
        if (!keycloakIds.isEmpty()) {
            for (Object[] row : termsAcceptances.findLastAcceptedVersions(keycloakIds)) {
                versions.put((String) row[0], (String) row[1]);
            }
        }
        return versions;
    }

    /** The known roles among these, in the screen's order. */
    static List<String> roleNames(List<KeycloakRole> roles) {
        Set<String> names = roles.stream().map(KeycloakRole::name).collect(Collectors.toSet());
        return KNOWN_ROLES.stream().filter(names::contains).toList();
    }

    private static AdminUser toAdminUser(
            KeycloakUser user, boolean enabled, List<String> roles, String accepted, String current) {
        OffsetDateTime createdAt = user.createdTimestamp() == null
                ? null
                : OffsetDateTime.ofInstant(Instant.ofEpochMilli(user.createdTimestamp()), ZoneOffset.UTC);
        return new AdminUser(user.id(), user.name(), user.email(), user.attribute("sector"), user.attribute("cpf"),
                enabled, roles,
                accepted, current, createdAt);
    }
}
