package com.akpedia.server.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akpedia.server.client.KeycloakAdminClient;
import com.akpedia.server.client.KeycloakRole;
import com.akpedia.server.entity.User;
import com.akpedia.server.exception.LastAdminException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.AuditLogRepository;
import com.akpedia.server.repository.ProfileChangeRequestRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * A user deleting their own account ("Excluir minha conta").
 *
 * <p>In this order: the deletion is written to the {@link AccountDeletionLog} first, so it is never
 * lost even if a later step fails; then the Keycloak account is deleted, and the {@code users}
 * row anonymized rather than deleted (documents and terms acceptances point at it).
 *
 * <p>The audit trail keeps what the user did, pseudonymized: their entries lose their email, IP
 * and user agent and point at the anonymized row (the audit screen shows "Usuário removido #id");
 * the entries others wrote about them keep the Keycloak id but lose the email, and requests that
 * searched for that email lose their query string.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    /** Users read per Keycloak call while looking for another admin. */
    static final int ADMIN_SEARCH_PAGE = AdminUserService.ADMIN_SEARCH_PAGE;

    private final AccountDeletionLog deletions;
    private final KeycloakAdminClient keycloak;
    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final ProfileChangeRequestRepository changeRequests;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public AccountDeletionService(AccountDeletionLog deletions, KeycloakAdminClient keycloak,
            UserRepository users, AuditLogRepository auditLogs, ProfileChangeRequestRepository changeRequests,
            AuditService audit, PlatformTransactionManager transactionManager) {
        this(deletions, keycloak, users, auditLogs, changeRequests, audit, transactionManager, Clock.systemUTC());
    }

    AccountDeletionService(AccountDeletionLog deletions, KeycloakAdminClient keycloak,
            UserRepository users, AuditLogRepository auditLogs, ProfileChangeRequestRepository changeRequests,
            AuditService audit, PlatformTransactionManager transactionManager, Clock clock) {
        this.deletions = deletions;
        this.keycloak = keycloak;
        this.users = users;
        this.auditLogs = auditLogs;
        this.changeRequests = changeRequests;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Deletes the account behind this Keycloak id.
     *
     * @throws LastAdminException if it is the only enabled admin
     * @throws com.akpedia.server.exception.AccountDeletionLogException if the log cannot be
     *         written; nothing was deleted then
     * @throws com.akpedia.server.exception.KeycloakUnavailableException if Keycloak failed; the
     *         deletion is in the log, so trying again, or the next replay, finishes it
     */
    public void deleteOwnAccount(String keycloakId) {
        if (isOnlyAdmin(keycloakId)) {
            throw new LastAdminException();
        }
        Long userId = users.findIdByKeycloakId(keycloakId).orElse(null);
        deletions.append(keycloakId, userId);
        keycloak.deleteUser(keycloakId);
        transaction.executeWithoutResult(status -> {
            Long anonymized = anonymize(keycloakId, userId);
            // After anonymizing: the event keeps no email or IP either (AuditService leaves them
            // out for a deleted account).
            audit.record(anonymized, AuditService.ACCOUNT_DELETED, AuditService.USER, anonymized, null);
        });
        log.info("Account {} deleted by its user", keycloakId);
    }

    /**
     * Anonymizes the account's row, if it is still there, and pseudonymizes the audit entries by
     * and about it, and deletes its e-mail and CPF change requests. Safe to run again: what is
     * already done stays as it is.
     *
     * @return the row's id, or {@code null} if the account has none
     */
    Long anonymize(String keycloakId, Long userId) {
        List<User> rows = users.findDeletedAccountRows(keycloakId, userId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // Already anonymized by an earlier run, the row no longer matches; a row with that id that
        // is someone else's now is not this account's.
        Long anonymized = userId == null ? null : users.findById(userId)
                .filter(row -> row.getDeletedAt() != null)
                .map(User::getId)
                .orElse(null);
        // Every email the account was known by, to find the searches for it: the row's, and its
        // tokens' (it may have none, if it never did anything that needed one).
        Set<String> emails = new HashSet<>();
        auditLogs.findEmailsOf(keycloakId).forEach(email -> emails.add(email.toLowerCase(Locale.ROOT)));
        for (User row : rows) {
            if (row.getDeletedAt() == null) {
                emails.add(row.getEmail().toLowerCase(Locale.ROOT));
            }
            row.anonymize(now);
            anonymized = row.getId();
        }
        users.flush();
        auditLogs.stripPersonalData(keycloakId, anonymized);
        if (anonymized != null) {
            auditLogs.linkToRow(keycloakId, anonymized);
            // The e-mail and CPF the account asked for are its personal data; the decisions it
            // took as an admin stay, without its name.
            changeRequests.deleteByUserId(anonymized);
            changeRequests.clearDecidedBy(anonymized);
        }
        auditLogs.stripTargetEmail(keycloakId);
        for (String email : emails) {
            auditLogs.stripQueriesContaining(email);
            String encoded = URLEncoder.encode(email, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (!encoded.equals(email)) {
                auditLogs.stripQueriesContaining(encoded);
            }
        }
        return anonymized;
    }

    /**
     * Whether, once this account is gone, no enabled user would have ADMIN. The service account
     * cannot list a role's users, so the realm's users are looked at, until another admin turns up.
     */
    private boolean isOnlyAdmin(String keycloakId) {
        try {
            if (!hasAdmin(keycloakId)) {
                return false;
            }
        } catch (UserNotFoundException e) {
            // Already gone from Keycloak: an earlier attempt got that far.
            return false;
        }
        return !AdminUserService.hasOtherEnabledAdmin(keycloak, keycloakId);
    }

    private boolean hasAdmin(String keycloakId) {
        return keycloak.effectiveRealmRoles(keycloakId).stream()
                .map(KeycloakRole::name)
                .anyMatch(AdminUserService.ADMIN::equals);
    }

    /** What a replay did with one entry. */
    record Replayed(boolean keycloakUserDeleted, boolean rowAnonymized) {
    }

    /**
     * Does one logged deletion again: the Keycloak account if it came back, the row if it is
     * linked again. Used by {@link AccountDeletionReplay}.
     */
    Replayed replay(AccountDeletionLog.Entry entry) {
        boolean deleted = keycloak.deleteUser(entry.keycloakId());
        Boolean anonymized = transaction.execute(status -> {
            // Only a row brought back by a restore is still found: an anonymized one no longer matches.
            boolean restored = !users.findDeletedAccountRows(entry.keycloakId(), entry.userId()).isEmpty();
            anonymize(entry.keycloakId(), entry.userId());
            return restored;
        });
        return new Replayed(deleted, Boolean.TRUE.equals(anonymized));
    }
}
