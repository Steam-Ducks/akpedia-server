package com.akpedia.server.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.akpedia.server.exception.AccountDeletionLogException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Every deleted account, one JSON line each, in a file outside the database.
 *
 * <p>Outside on purpose: the realm and the app share one Postgres, so restoring a backup brings
 * deleted accounts back, and this file is how they are deleted again (see
 * {@link AccountDeletionReplay}). Keep it out of the backups restored over the database. It is
 * kept forever, so a line holds no name or email, only ids.
 *
 * <p>The Keycloak ids in it are also kept in memory: a deleted user's token stays valid for a few
 * minutes, and the app refuses it and records nothing personal about it.
 */
@Component
public class AccountDeletionLog {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionLog.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path file;
    private final Clock clock;
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    /** The anonymized row of each deleted account that had one. */
    private final Map<String, Long> rows = new ConcurrentHashMap<>();

    @Autowired
    public AccountDeletionLog(
            @Value("${akpedia.account-deletion.log-file:./data/account-deletions.jsonl}") String file) {
        // A plain string, not a Path: Spring's conversion would look a relative path up on the classpath.
        this(Path.of(file), Clock.systemUTC());
    }

    AccountDeletionLog(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        try {
            entries().forEach(this::remember);
        } catch (AccountDeletionLogException e) {
            // Not fatal here: the replay and the next deletion report it again.
            log.error("Could not read the account deletion log {}", file, e);
        }
    }

    /**
     * Appends a deletion and waits for it to reach the disk, before anything is deleted.
     *
     * @param userId the account's row in {@code users}, if it has one
     * @throws AccountDeletionLogException if the file cannot be written
     */
    public synchronized Entry append(String keycloakId, Long userId) {
        Entry entry = new Entry(Instant.now(clock).toString(), keycloakId, userId, Entry.SELF);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, JSON.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC);
        } catch (IOException e) {
            throw new AccountDeletionLogException(e);
        }
        remember(entry);
        return entry;
    }

    private void remember(Entry entry) {
        deleted.add(entry.keycloakId());
        if (entry.userId() != null) {
            rows.put(entry.keycloakId(), entry.userId());
        }
    }

    /** Every deletion so far, oldest first. A line that cannot be read is skipped and logged. */
    public synchronized List<Entry> entries() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new AccountDeletionLogException(e);
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                Entry entry = JSON.readValue(line, Entry.class);
                if (entry.keycloakId() == null || entry.keycloakId().isBlank()) {
                    throw new IllegalArgumentException("no keycloak_id");
                }
                entries.add(entry);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                log.warn("Skipping line {} of {}: not a deletion entry", i + 1, file);
            }
        }
        return entries;
    }

    /** Whether this Keycloak account was deleted. */
    public boolean isDeleted(String keycloakId) {
        return keycloakId != null && deleted.contains(keycloakId);
    }

    /** The {@code users} row a deleted account had, as logged; empty if it had none. */
    public Optional<Long> rowOf(String keycloakId) {
        return keycloakId == null ? Optional.empty() : Optional.ofNullable(rows.get(keycloakId));
    }

    public Path file() {
        return file;
    }

    /**
     * One line of the file.
     *
     * @param time      when, ISO-8601 UTC
     * @param userId    the row in {@code users}, if the account had one
     * @param deletedBy who deleted it; only {@code self} for now
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Entry(
            String time,
            @JsonProperty("keycloak_id") String keycloakId,
            @JsonProperty("user_id") Long userId,
            @JsonProperty("deleted_by") String deletedBy) {

        static final String SELF = "self";
    }
}
