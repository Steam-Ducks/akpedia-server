package com.akpedia.server.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.akpedia.server.dto.TermsHistoryEntry;

/**
 * The terms history: acceptances ({@code terms_acceptances}), and the optional permissions turned
 * on or off afterwards and the refusals, both from the audit trail. In SQL, so one query pages
 * through the three together.
 *
 * <p>An audit entry shows the label of the version it points at, as acceptances do; the label copied
 * into its details only when it points at none (a refusal of an unknown version). A change of an
 * optional permission that points at no version is left out: those came before the permissions
 * were part of the terms, and their {@code version} was not a version of the terms.
 *
 * <p>For an audit entry, the user's row wins over the e-mail copied in: a deleted account shows
 * its anonymized row, as its acceptances do. A refusal by someone with no row keeps that e-mail.
 */
@Repository
public class TermsHistoryRepository {

    private static final String EVENTS = """
            WITH events AS (
                SELECT 'accepted' AS action, a.id, v.version, u.id AS user_id, u.name AS user_name,
                       u.email AS user_email, a.accepted_at AS at
                FROM terms_acceptances a
                JOIN users u ON u.id = a.user_id
                JOIN terms_versions v ON v.id = a.terms_version_id
                UNION ALL
                SELECT CASE l.action WHEN 'OPTIONAL_PERMISSION_CHOSEN' THEN 'updated' ELSE 'declined' END,
                       l.id, COALESCE(v.version, l.details ->> 'version'), u.id, u.name,
                       CASE WHEN u.id IS NULL THEN l.user_email ELSE u.email END, l.created_at
                FROM audit_logs l
                LEFT JOIN users u ON u.id = l.user_id
                LEFT JOIN terms_versions v ON v.id = l.entity_id AND l.entity_type = 'TERMS_VERSION'
                WHERE l.action = 'TERMS_DECLINED'
                   OR (l.action = 'OPTIONAL_PERMISSION_CHOSEN' AND v.id IS NOT NULL)
            )
            SELECT %s FROM events
            WHERE (:version = '' OR lower(version) = lower(:version))
              AND (:user = '' OR lower(user_name) LIKE :user OR lower(user_email) LIKE :user)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TermsHistoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of the history, newest first, of {@code version} (any case; empty for every version)
     * by users whose name or e-mail, in lower case, matches {@code user} (a LIKE pattern; empty for
     * every user).
     */
    public List<TermsHistoryEntry> search(String version, String user, int page, int size) {
        MapSqlParameterSource params = params(version, user)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        return jdbc.query(EVENTS.formatted("*") + " ORDER BY at DESC, id DESC LIMIT :limit OFFSET :offset",
                params, TermsHistoryRepository::entry);
    }

    /** How many entries {@link #search} pages through. */
    public long count(String version, String user) {
        Long count = jdbc.queryForObject(EVENTS.formatted("count(*)"), params(version, user), Long.class);
        return count == null ? 0 : count;
    }

    private static MapSqlParameterSource params(String version, String user) {
        return new MapSqlParameterSource().addValue("version", version).addValue("user", user);
    }

    private static TermsHistoryEntry entry(ResultSet rs, int row) throws SQLException {
        Long userId = rs.getObject("user_id", Long.class);
        return new TermsHistoryEntry(rs.getString("action"), rs.getLong("id"), rs.getString("version"),
                userId, rs.getString("user_name"), rs.getString("user_email"),
                rs.getObject("at", OffsetDateTime.class));
    }
}
