package io.github.sidneyroberto9.sentret.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps sessions in the host application's database through its {@link JdbcTemplate}. Every
 * operation is a single statement, so no transaction is needed. Times are stored as epoch
 * milliseconds (see {@code db/sentret-schema.sql}).
 */
@Slf4j
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
    private final boolean createTable;

    public JdbcSentretSessionStore(JdbcTemplate jdbc) {
        this(jdbc, true);
    }

    /** {@code createTable=false} only reports a missing table, for schemas owned by Flyway/Liquibase. */
    public JdbcSentretSessionStore(JdbcTemplate jdbc, boolean createTable) {
        this.jdbc = jdbc;
        this.createTable = createTable;
    }

    /**
     * Runs once the context is up (after schema initializers such as spring.sql.init or Flyway), so
     * a table they create is never touched. Creates a missing table unless told not to. Never stops
     * the startup: a database that is down or a user without CREATE TABLE is only logged.
     */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            if (tableExists()) {
                return;
            }
        } catch (DataAccessException e) {
            log.warn("[sentret] Could not check the sentret_sessions table at startup: {}", e.getMessage());
            return;
        }

        if (!createTable) {
            log.error("[sentret] Table sentret_sessions not found: every login will fail until it exists. "
                    + "Create it with db/sentret-schema.sql (shipped in the jar, see README).");
            return;
        }

        try {
            createTable();
        } catch (DataAccessException e) {
            log.error("[sentret] Could not create table sentret_sessions: every login will fail until it exists. "
                    + "Create it with db/sentret-schema.sql (shipped in the jar, see README) or let the database "
                    + "user run CREATE TABLE.", e);
        }
    }

    /**
     * The DDL of {@code db/sentret-schema.sql}, with the binary collation that keeps the id
     * case-sensitive on the databases that ignore case by default.
     */
    public static String createTableSql(String databaseProductName) {
        String product = databaseProductName == null ? "" : databaseProductName.toLowerCase(Locale.ROOT);
        String collation = "";

        if (product.contains("mysql") || product.contains("mariadb")) {
            collation = " CHARACTER SET ascii COLLATE ascii_bin";
        } else if (product.contains("sql server")) {
            collation = " COLLATE Latin1_General_BIN2";
        }

        return "CREATE TABLE sentret_sessions ("
                + "session_id VARCHAR(20)" + collation + " NOT NULL PRIMARY KEY, "
                + "user_id VARCHAR(255) NOT NULL, "
                + "email VARCHAR(255), "
                + "created_at BIGINT NOT NULL, "
                + "expires_at BIGINT NOT NULL, "
                + "last_accessed_at BIGINT NOT NULL)";
    }

    private boolean tableExists() {
        try {
            jdbc.query("SELECT session_id FROM sentret_sessions WHERE 1 = 0", rs -> {
            });
            return true;
        } catch (BadSqlGrammarException e) {
            return false;
        }
    }

    private void createTable() {
        String product = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());

        try {
            jdbc.execute(createTableSql(product));
        } catch (DataAccessException e) {
            // Another instance starting at the same time created it first (and creates the indexes).
            if (tableExists()) {
                return;
            }
            throw e;
        }

        jdbc.execute("CREATE INDEX idx_sentret_sessions_user_id ON sentret_sessions (user_id)");
        jdbc.execute("CREATE INDEX idx_sentret_sessions_expires_at ON sentret_sessions (expires_at)");
        log.info("[sentret] Created table sentret_sessions ({}).", product);
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
