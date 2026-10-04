package io.github.sidneyroberto9.sentret.store;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.Optional;

/**
 * Keeps sessions in the host application's database through its {@link JdbcTemplate}. Every
 * operation is a single statement, so no transaction is needed. Times are stored as epoch
 * milliseconds (see {@code db/sentret-schema.sql}).
 */
@Slf4j
@RequiredArgsConstructor
public class JdbcSentretSessionStore implements SentretSessionStore, SmartInitializingSingleton {

    private static final String COLUMNS = "session_id, user_id, email, created_at, expires_at, last_accessed_at";

    private static final RowMapper<SentretSession> ROW_MAPPER = (rs, rowNum) -> new SentretSession(
            rs.getString("session_id"),
            rs.getString("user_id"),
            rs.getString("email"),
            Instant.ofEpochMilli(rs.getLong("created_at")),
            Instant.ofEpochMilli(rs.getLong("expires_at")),
            Instant.ofEpochMilli(rs.getLong("last_accessed_at")));

    private final JdbcTemplate jdbc;

    /**
     * Runs once the context is up (after schema initializers such as spring.sql.init or Flyway).
     * A missing table only shows up as failed logins otherwise, so it is reported here. Never stops
     * the startup: a database that is down right now is only logged.
     */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            jdbc.query("SELECT session_id FROM sentret_sessions WHERE 1 = 0", rs -> {
            });
        } catch (BadSqlGrammarException e) {
            log.error("[sentret] Table sentret_sessions not found: every login will fail until it exists. "
                    + "Create it with db/sentret-schema.sql (shipped in the jar, see README).");
        } catch (DataAccessException e) {
            log.warn("[sentret] Could not check the sentret_sessions table at startup: {}", e.getMessage());
        }
    }

    @Override
    public void insert(SentretSession session) {
        jdbc.update("INSERT INTO sentret_sessions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                session.sessionId(),
                session.userId(),
                session.email(),
                session.createdAt().toEpochMilli(),
                session.expiresAt().toEpochMilli(),
                session.lastAccessedAt().toEpochMilli());
    }

    @Override
    public Optional<SentretSession> findBySessionId(String sessionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sentret_sessions WHERE session_id = ?", ROW_MAPPER, sessionId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean updateLastAccessedAt(String sessionId, Instant lastAccessedAt) {
        return jdbc.update("UPDATE sentret_sessions SET last_accessed_at = ? WHERE session_id = ?",
                lastAccessedAt.toEpochMilli(), sessionId) > 0;
    }

    @Override
    public boolean updateExpiresAt(String sessionId, Instant expiresAt, Instant lastAccessedAt) {
        return jdbc.update("UPDATE sentret_sessions SET expires_at = ?, last_accessed_at = ? WHERE session_id = ?",
                expiresAt.toEpochMilli(), lastAccessedAt.toEpochMilli(), sessionId) > 0;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        jdbc.update("DELETE FROM sentret_sessions WHERE session_id = ?", sessionId);
    }

    @Override
    public void deleteByUserId(String userId) {
        jdbc.update("DELETE FROM sentret_sessions WHERE user_id = ?", userId);
    }

    @Override
    public void deleteExpired(Instant now) {
        jdbc.update("DELETE FROM sentret_sessions WHERE expires_at < ?", now.toEpochMilli());
    }
}
