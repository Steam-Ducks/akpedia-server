package com.akpedia.server.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.client.KeycloakAdminClient;
import com.akpedia.server.client.KeycloakUser;
import com.akpedia.server.dto.AdminProfileUpdateRequest;
import com.akpedia.server.dto.ChangeRequest;
import com.akpedia.server.dto.ChangeRequestStatusFilter;
import com.akpedia.server.dto.NewChangeRequest;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.Profile;
import com.akpedia.server.dto.UpdateProfileRequest;
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
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.ProfileChangeRequestRepository;
import com.akpedia.server.repository.SectorRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * The profile: what the user changes themself (first and last name, sector), what they ask an
 * admin to change (e-mail and CPF), and the admin's own edits and decisions on those requests.
 *
 * <p>The profile lives in Keycloak; this changes it there through the Admin API and mirrors the
 * name, e-mail and sector on the user's {@code users} row. The token only carries a change from
 * its next refresh. The realm's user profile lets only an admin edit the e-mail and CPF, so the
 * user cannot change them in Keycloak's account console either.
 *
 * <p>The audit trail names the fields changed, never their values.
 */
@Service
public class ProfileService {

    static final int MAX_REASON = 500;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    /** {@code users.name} is VARCHAR(150). */
    private static final int MAX_ROW_NAME = 150;

    private final KeycloakAdminClient keycloak;
    private final UserRepository users;
    private final SectorRepository sectors;
    private final ProfileChangeRequestRepository requests;
    private final AuditService audit;
    private final Clock clock;

    @Autowired
    public ProfileService(KeycloakAdminClient keycloak, UserRepository users, SectorRepository sectors,
            ProfileChangeRequestRepository requests, AuditService audit) {
        this(keycloak, users, sectors, requests, audit, Clock.systemUTC());
    }

    ProfileService(KeycloakAdminClient keycloak, UserRepository users, SectorRepository sectors,
            ProfileChangeRequestRepository requests, AuditService audit, Clock clock) {
        this.keycloak = keycloak;
        this.users = users;
        this.sectors = sectors;
        this.requests = requests;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The user's profile, as Keycloak has it now.
     *
     * @throws UserNotFoundException if the account is gone from Keycloak
     */
    public Profile profile(String keycloakId) {
        return toProfile(keycloak.user(keycloakId));
    }

    /**
     * The user changes their own first and last name and sector.
     *
     * @param userId the user's row
     * @throws InvalidProfileException if a field is missing or invalid, or Keycloak refused one
     * @throws NothingChangedException if it is all as it was
     */
    @Transactional
    public Profile update(String keycloakId, Long userId, UpdateProfileRequest request) {
        if (request == null) {
            throw new InvalidProfileException("Informe o nome, o sobrenome e o setor.");
        }
        String firstName = ProfileRules.required(request.firstName(), "o nome", InvalidProfileException::new);
        String lastName = ProfileRules.required(request.lastName(), "o sobrenome", InvalidProfileException::new);
        String sector = ProfileRules.sector(request.sector(), InvalidProfileException::new);

        KeycloakUser current = keycloak.user(keycloakId);
        List<String> fields = changedFields(current, firstName, lastName, current.email(), current.attribute("cpf"),
                sector);
        if (fields.isEmpty()) {
            throw new NothingChangedException("Nada foi alterado.");
        }
        updateKeycloak(keycloakId, representation -> {
            representation.put("firstName", firstName);
            representation.put("lastName", lastName);
            attributes(representation).put("sector", new ArrayList<>(List.of(sector)));
        }, null);
        users.findById(userId).ifPresent(row -> mirror(row, firstName, lastName, null, sector));
        audit.record(userId, AuditService.PROFILE_UPDATED, AuditService.USER, userId,
                AuditService.details("fields", fields));
        return toProfile(keycloak.user(keycloakId));
    }

    /**
     * An admin edits any field of a user's profile, e-mail and CPF included. The e-mail is
     * also the login, and taken as verified. A pending request the edit makes moot is left for
     * the admin to reject.
     *
     * @param id the user's Keycloak id
     * @throws InvalidProfileException if a field is missing or invalid, or Keycloak refused one
     * @throws NothingChangedException if it is all as it was
     * @throws UserExistsException     if another user has that e-mail
     * @throws UserNotFoundException   if there is no such user
     */
    @Transactional
    public void updateAsAdmin(String id, AdminProfileUpdateRequest request) {
        if (request == null) {
            throw new InvalidProfileException("Informe o nome, o sobrenome, o e-mail, o CPF e o setor.");
        }
        String firstName = ProfileRules.required(request.firstName(), "o nome", InvalidProfileException::new);
        String lastName = ProfileRules.required(request.lastName(), "o sobrenome", InvalidProfileException::new);
        String email = ProfileRules.email(request.email(), InvalidProfileException::new);
        String cpf = ProfileRules.cpf(request.cpf(), InvalidProfileException::new);
        String sector = ProfileRules.sector(request.sector(), InvalidProfileException::new);

        KeycloakUser current = keycloak.user(id);
        List<String> fields = changedFields(current, firstName, lastName, email, cpf, sector);
        if (fields.isEmpty()) {
            throw new NothingChangedException("Nada foi alterado.");
        }
        boolean emailChanged = fields.contains("email");
        User row = users.findByKeycloakId(id).orElse(null);
        if (emailChanged) {
            ensureEmailFree(id, row, email);
        }
        updateKeycloak(id, representation -> {
            representation.put("firstName", firstName);
            representation.put("lastName", lastName);
            if (emailChanged) {
                representation.put("email", email);
                representation.put("emailVerified", true);
            }
            Map<String, List<String>> attributes = attributes(representation);
            attributes.put("cpf", new ArrayList<>(List.of(cpf)));
            attributes.put("sector", new ArrayList<>(List.of(sector)));
        }, emailChanged ? email : null);
        if (row != null) {
            mirror(row, firstName, lastName, emailChanged ? email : null, sector);
        }
        audit.record(null, AuditService.USER_UPDATED, AuditService.USER, row == null ? null : row.getId(),
                AuditService.details("target_keycloak_id", id, "fields", fields));
    }

    /** The user's own requests, newest first, whatever their status. */
    @Transactional(readOnly = true)
    public List<ChangeRequest> requests(Long userId) {
        return requests.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream().map(ChangeRequest::of).toList();
    }

    /**
     * The user asks an admin to change their e-mail or CPF.
     *
     * @param userId the user's row
     * @throws InvalidChangeRequestException if the field or value is invalid, the value is the
     *         current one, or the reason too long
     * @throws ChangeRequestPendingException if a request for that field is waiting already
     * @throws UserExistsException           if another user has that e-mail
     */
    @Transactional
    public ChangeRequest request(String keycloakId, Long userId, NewChangeRequest body) {
        if (body == null) {
            throw new InvalidChangeRequestException("Informe o campo e o novo valor.");
        }
        ProfileField field = ProfileField.fromApiName(body.field());
        if (field == null) {
            throw new InvalidChangeRequestException("Campo inválido: só o e-mail (email) ou o CPF (cpf).");
        }
        String value = field == ProfileField.EMAIL
                ? ProfileRules.email(body.newValue(), InvalidChangeRequestException::new)
                : ProfileRules.cpf(body.newValue(), InvalidChangeRequestException::new);
        String reason = optionalText(body.reason(), "O motivo", InvalidChangeRequestException::new);

        KeycloakUser current = keycloak.user(keycloakId);
        if (value.equals(currentValue(current, field))) {
            throw new InvalidChangeRequestException(field == ProfileField.EMAIL
                    ? "O novo e-mail é igual ao atual."
                    : "O novo CPF é igual ao atual.");
        }
        if (requests.existsByUserIdAndFieldAndStatus(userId, field, ChangeRequestStatus.PENDING)) {
            throw pending(field);
        }
        if (field == ProfileField.EMAIL) {
            ensureEmailFree(keycloakId, null, value);
        }
        ProfileChangeRequest saved;
        try {
            saved = requests.saveAndFlush(new ProfileChangeRequest(users.getReferenceById(userId), field, value,
                    reason));
        } catch (DataIntegrityViolationException e) {
            // Another request for the field got in first: the partial unique index caught it.
            throw pending(field);
        }
        audit.record(userId, AuditService.PROFILE_CHANGE_REQUESTED, AuditService.PROFILE_CHANGE_REQUEST,
                saved.getId(), AuditService.details("field", field.apiName()));
        return ChangeRequest.of(saved);
    }

    /**
     * The user withdraws a request of theirs that was not decided yet. It is deleted.
     *
     * @throws ChangeRequestNotFoundException   if there is no such request of theirs
     * @throws ChangeRequestNotPendingException if it was decided already
     */
    @Transactional
    public void cancel(Long userId, Long requestId) {
        ProfileChangeRequest request = requests.findForDecision(requestId)
                .filter(found -> Objects.equals(found.getUser().getId(), userId))
                .orElseThrow(() -> new ChangeRequestNotFoundException(requestId));
        if (!request.isPending()) {
            throw notPending();
        }
        requests.delete(request);
        audit.record(userId, AuditService.PROFILE_CHANGE_CANCELLED, AuditService.PROFILE_CHANGE_REQUEST, requestId,
                AuditService.details("field", request.getField().apiName()));
    }

    /**
     * One page of the requests, newest first, for the admin.
     *
     * @param status {@code PENDING} when {@code null}
     * @throws InvalidPageRequestException if the page is negative or the size out of range
     */
    @Transactional(readOnly = true)
    public PageResponse<ChangeRequest> list(ChangeRequestStatusFilter status, Integer requestedPage,
            Integer requestedSize) {
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPageRequestException(MAX_PAGE_SIZE);
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        ChangeRequestStatusFilter filter = status == null ? ChangeRequestStatusFilter.PENDING : status;
        return PageResponse.from(filter == ChangeRequestStatusFilter.ALL
                ? requests.findAll(pageable)
                : requests.findByStatus(ChangeRequestStatus.valueOf(filter.name()), pageable), ChangeRequest::of);
    }

    /**
     * The admin approves a request: the new value is applied in Keycloak (a new e-mail is also
     * the new login, taken as verified) and on the user's row.
     *
     * @param adminId the admin's row
     * @throws ChangeRequestNotFoundException   if there is no such request
     * @throws ChangeRequestNotPendingException if it was decided already
     * @throws UserExistsException              if the e-mail was taken meanwhile
     */
    @Transactional
    public ChangeRequest approve(Long requestId, Long adminId) {
        ProfileChangeRequest request = pendingRequest(requestId);
        User row = request.getUser();
        String keycloakId = row.getKeycloakId();
        if (keycloakId == null) {
            throw new UserNotFoundException(row.getId());
        }
        String value = request.getNewValue();
        if (request.getField() == ProfileField.EMAIL) {
            ensureEmailFree(keycloakId, row, value);
            updateKeycloak(keycloakId, representation -> {
                representation.put("email", value);
                representation.put("emailVerified", true);
            }, value);
            row.setEmail(value);
        } else {
            updateKeycloak(keycloakId, representation -> attributes(representation)
                    .put("cpf", new ArrayList<>(List.of(value))), null);
        }
        request.decide(ChangeRequestStatus.APPROVED, users.getReferenceById(adminId), null, OffsetDateTime.now(clock));
        audit.record(adminId, AuditService.PROFILE_CHANGE_APPROVED, AuditService.PROFILE_CHANGE_REQUEST, requestId,
                AuditService.details("field", request.getField().apiName(), "target_keycloak_id", keycloakId));
        return ChangeRequest.of(request);
    }

    /**
     * The admin rejects a request; nothing changes but the request.
     *
     * @param note shown to the user, optional
     * @throws ChangeRequestNotFoundException   if there is no such request
     * @throws ChangeRequestNotPendingException if it was decided already
     * @throws InvalidChangeRequestException    if the note is too long
     */
    @Transactional
    public ChangeRequest reject(Long requestId, Long adminId, String note) {
        String cleaned = optionalText(note, "A observação", InvalidChangeRequestException::new);
        ProfileChangeRequest request = pendingRequest(requestId);
        request.decide(ChangeRequestStatus.REJECTED, users.getReferenceById(adminId), cleaned,
                OffsetDateTime.now(clock));
        audit.record(adminId, AuditService.PROFILE_CHANGE_REJECTED, AuditService.PROFILE_CHANGE_REQUEST, requestId,
                AuditService.details("field", request.getField().apiName(),
                        "target_keycloak_id", request.getUser().getKeycloakId()));
        return ChangeRequest.of(request);
    }

    private ProfileChangeRequest pendingRequest(Long requestId) {
        ProfileChangeRequest request = requests.findForDecision(requestId)
                .orElseThrow(() -> new ChangeRequestNotFoundException(requestId));
        if (!request.isPending()) {
            throw notPending();
        }
        return request;
    }

    /**
     * Refuses an e-mail that another Keycloak account has, or another {@code users} row: the
     * column is unique too.
     *
     * @param row the account's own row, if it has one
     */
    private void ensureEmailFree(String keycloakId, User row, String email) {
        boolean taken = keycloak.usersWithEmail(email).stream().anyMatch(other -> !other.id().equals(keycloakId));
        if (!taken) {
            taken = users.findByEmail(email)
                    .filter(other -> row == null ? !keycloakId.equals(other.getKeycloakId())
                            : !other.getId().equals(row.getId()))
                    .isPresent();
        }
        if (taken) {
            throw new UserExistsException(email);
        }
    }

    /** Keycloak's refusal of a field is the profile's: the screen shows it next to the form. */
    private void updateKeycloak(String id, Consumer<Map<String, Object>> change, String email) {
        try {
            keycloak.updateUser(id, change, email);
        } catch (InvalidUserException e) {
            throw new InvalidProfileException(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> attributes(Map<String, Object> representation) {
        return (Map<String, List<String>>) representation.get("attributes");
    }

    /** Copies the change to the user's row; {@code null} leaves the e-mail as it is. */
    private void mirror(User row, String firstName, String lastName, String email, String sector) {
        String name = (firstName + " " + lastName).strip();
        row.setName(name.length() > MAX_ROW_NAME ? name.substring(0, MAX_ROW_NAME) : name);
        if (email != null) {
            row.setEmail(email);
        }
        if (!sector.equals(row.getSector().getName())) {
            row.setSector(sectors.findByName(sector).orElseGet(() -> sectors.saveAndFlush(new Sector(sector, null))));
        }
    }

    /** The API names of the fields that differ from the account's, in the form's order. */
    private static List<String> changedFields(KeycloakUser current, String firstName, String lastName, String email,
            String cpf, String sector) {
        List<String> fields = new ArrayList<>();
        if (!firstName.equals(current.firstName())) {
            fields.add("first_name");
        }
        if (!lastName.equals(current.lastName())) {
            fields.add("last_name");
        }
        if (current.email() == null || !email.equals(current.email().toLowerCase(Locale.ROOT))) {
            fields.add("email");
        }
        if (!cpf.equals(current.attribute("cpf"))) {
            fields.add("cpf");
        }
        if (!sector.equals(current.attribute("sector"))) {
            fields.add("sector");
        }
        return fields;
    }

    private static String currentValue(KeycloakUser user, ProfileField field) {
        if (field == ProfileField.EMAIL) {
            return user.email() == null ? null : user.email().toLowerCase(Locale.ROOT);
        }
        return user.attribute("cpf");
    }

    /** Trimmed, {@code null} when blank, refused past {@link #MAX_REASON} characters. */
    private static String optionalText(String value, String what,
            Function<String, ? extends RuntimeException> invalid) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > MAX_REASON) {
            throw invalid.apply("%s deve ter no máximo %d caracteres.".formatted(what, MAX_REASON));
        }
        return stripped;
    }

    private static ChangeRequestPendingException pending(ProfileField field) {
        return new ChangeRequestPendingException(field == ProfileField.EMAIL
                ? "Já há uma solicitação de alteração do e-mail aguardando um administrador."
                : "Já há uma solicitação de alteração do CPF aguardando um administrador.");
    }

    private static ChangeRequestNotPendingException notPending() {
        return new ChangeRequestNotPendingException("Esta solicitação já foi analisada.");
    }

    private Profile toProfile(KeycloakUser user) {
        List<String> roles = AdminUserService.roleNames(keycloak.effectiveRealmRoles(user.id()));
        return new Profile(user.firstName(), user.lastName(), user.email(), user.attribute("cpf"),
                user.attribute("sector"), roles, Profile.EDITABLE, Profile.REQUESTABLE);
    }
}
