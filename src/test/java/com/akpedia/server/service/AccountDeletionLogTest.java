package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.akpedia.server.exception.AccountDeletionLogException;

class AccountDeletionLogTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    @Test
    @DisplayName("appends one JSON line per deletion, ids only, creating the folder")
    void appendsLines() throws IOException {
        Path file = dir.resolve("sub/deletions.jsonl");
        AccountDeletionLog log = new AccountDeletionLog(file, CLOCK);

        log.append("kc-1", 42L);
        log.append("kc-2", null);

        assertThat(Files.readAllLines(file)).containsExactly(
                "{\"time\":\"2026-11-08T12:00:00Z\",\"keycloak_id\":\"kc-1\",\"user_id\":42,\"deleted_by\":\"self\"}",
                "{\"time\":\"2026-11-08T12:00:00Z\",\"keycloak_id\":\"kc-2\",\"user_id\":null,\"deleted_by\":\"self\"}");
        assertThat(log.isDeleted("kc-1")).isTrue();
        assertThat(log.isDeleted("kc-3")).isFalse();
        assertThat(log.isDeleted(null)).isFalse();
    }

    @Test
    @DisplayName("reads the file back on start, skipping lines that are not entries")
    void readsBack() throws IOException {
        Path file = dir.resolve("deletions.jsonl");
        Files.writeString(file, """
                {"time":"2026-11-01T00:00:00Z","keycloak_id":"kc-1","user_id":7,"deleted_by":"self"}
                not json

                {"time":"2026-11-01T00:00:00Z","user_id":8}
                {"time":"2026-11-02T00:00:00Z","keycloak_id":"kc-2","deleted_by":"self","extra":true}
                """);

        AccountDeletionLog log = new AccountDeletionLog(file, CLOCK);

        assertThat(log.entries()).extracting(AccountDeletionLog.Entry::keycloakId).containsExactly("kc-1", "kc-2");
        assertThat(log.entries().get(0).userId()).isEqualTo(7L);
        assertThat(log.isDeleted("kc-2")).isTrue();
    }

    @Test
    @DisplayName("no file yet is no deletions; a file that cannot be written refuses the deletion")
    void missingAndUnwritable() throws IOException {
        assertThat(new AccountDeletionLog(dir.resolve("none.jsonl"), CLOCK).entries()).isEmpty();

        // Its folder is a file: neither readable nor writable.
        Path blocker = Files.writeString(dir.resolve("blocker"), "");
        AccountDeletionLog unwritable = new AccountDeletionLog(blocker.resolve("deletions.jsonl"), CLOCK);
        assertThatThrownBy(() -> unwritable.append("kc-1", 1L)).isInstanceOf(AccountDeletionLogException.class);
        assertThat(unwritable.isDeleted("kc-1")).isFalse();
    }
}
