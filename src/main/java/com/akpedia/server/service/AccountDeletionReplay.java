package com.akpedia.server.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.akpedia.server.dto.AccountDeletionReplayResponse;
import com.akpedia.server.exception.KeycloakUnavailableException;

/**
 * Does every deletion in the {@link AccountDeletionLog} again, for after a database backup was
 * restored: the restore brings deleted accounts back, in Keycloak and in {@code users} alike.
 *
 * <p>Runs on every start, in the background, trying again a few times while Keycloak is not up
 * yet; and on demand, from the admin's route. Idempotent: an account still deleted is left alone.
 */
@Component
public class AccountDeletionReplay {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionReplay.class);

    static final int STARTUP_ATTEMPTS = 10;

    private final AccountDeletionLog deletions;
    private final AccountDeletionService accounts;
    private final boolean onStartup;
    private final Duration retryDelay;

    public AccountDeletionReplay(AccountDeletionLog deletions, AccountDeletionService accounts,
            @Value("${akpedia.account-deletion.replay-on-startup:true}") boolean onStartup,
            @Value("${akpedia.account-deletion.replay-retry-delay:15s}") Duration retryDelay) {
        this.deletions = deletions;
        this.accounts = accounts;
        this.onStartup = onStartup;
        this.retryDelay = retryDelay;
    }

    /**
     * Replays every entry, oldest first.
     *
     * @throws KeycloakUnavailableException if Keycloak failed; the entries before it were replayed
     */
    public synchronized AccountDeletionReplayResponse replay() {
        List<AccountDeletionLog.Entry> entries = deletions.entries();
        int deletedAgain = 0;
        for (AccountDeletionLog.Entry entry : entries) {
            AccountDeletionService.Replayed replayed = accounts.replay(entry);
            if (replayed.keycloakUserDeleted() || replayed.rowAnonymized()) {
                deletedAgain++;
                log.info("Account {} had come back and was deleted again (Keycloak: {}, row: {})",
                        entry.keycloakId(), replayed.keycloakUserDeleted(), replayed.rowAnonymized());
            }
        }
        log.info("{} deletions replayed, {} restored accounts deleted again", entries.size(), deletedAgain);
        return new AccountDeletionReplayResponse(entries.size(), deletedAgain);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void replayOnStartup() {
        if (!onStartup) {
            return;
        }
        Thread thread = new Thread(this::replayWithRetries, "account-deletion-replay");
        thread.setDaemon(true);
        thread.start();
    }

    void replayWithRetries() {
        for (int attempt = 1; attempt <= STARTUP_ATTEMPTS; attempt++) {
            try {
                if (deletions.entries().isEmpty()) {
                    return;
                }
                replay();
                return;
            } catch (KeycloakUnavailableException e) {
                // Keycloak may still be starting.
                log.warn("Replaying the account deletions failed (attempt {} of {}): {}",
                        attempt, STARTUP_ATTEMPTS, e.getMessage());
            } catch (RuntimeException e) {
                log.error("Replaying the account deletions failed", e);
                return;
            }
            try {
                Thread.sleep(retryDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.error("Gave up replaying the account deletions after {} attempts; "
                + "POST /api/v1/admin/account-deletions/replay runs it again", STARTUP_ATTEMPTS);
    }
}
