package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.store.JdbcSentretSessionStore;
import io.github.sidneyroberto9.sentret.store.SentretSession;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real SQL against H2 with the shipped schema, so a typo in a statement or in the DDL
 * fails here instead of in a consumer's database.
 */
class JdbcSentretSessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00.123Z");

    private EmbeddedDatabase database;
    private JdbcSentretSessionStore store;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:db/sentret-schema.sql")
                .build();
        store = new JdbcSentretSessionStore(new JdbcTemplate(database));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static SentretSession session(String sessionId, String userId, Instant expiresAt) {
        return new SentretSession(sessionId, userId, userId + "@test.com", T0, expiresAt, T0);
    }

    @Test
    void insertThenFindReturnsTheSameSession() {
        SentretSession session = session("sid-1", "user-1", T0.plusSeconds(3600));

        store.insert(session);

        assertThat(store.findBySessionId("sid-1")).contains(session);
    }

    @Test
    void findReturnsEmptyForUnknownSession() {
        assertThat(store.findBySessionId("nope")).isEmpty();
    }

    /** Instant.now() carries micro/nanoseconds; the column keeps milliseconds. */
    @Test
    void insertTruncatesToMilliseconds() {
        Instant precise = Instant.parse("2026-01-01T10:00:00.123456789Z");
        store.insert(new SentretSession("sid-1", "user-1", null, precise, precise, precise));

        SentretSession found = store.findBySessionId("sid-1").orElseThrow();

        assertThat(found.createdAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00.123Z"));
        assertThat(found.email()).isNull();
    }

    @Test
    void updateLastAccessedAtChangesOnlyThatColumn() {
        store.insert(session("sid-1", "user-1", T0.plusSeconds(3600)));

        assertThat(store.updateLastAccessedAt("sid-1", T0.plusSeconds(60))).isTrue();

        SentretSession found = store.findBySessionId("sid-1").orElseThrow();
        assertThat(found.lastAccessedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(found.expiresAt()).isEqualTo(T0.plusSeconds(3600));
    }

    @Test
    void updateExpiresAtChangesExpiryAndLastAccess() {
        store.insert(session("sid-1", "user-1", T0.plusSeconds(60)));

        assertThat(store.updateExpiresAt("sid-1", T0.plusSeconds(7200), T0.plusSeconds(30))).isTrue();

        SentretSession found = store.findBySessionId("sid-1").orElseThrow();
        assertThat(found.expiresAt()).isEqualTo(T0.plusSeconds(7200));
        assertThat(found.lastAccessedAt()).isEqualTo(T0.plusSeconds(30));
    }

    /** A heartbeat or renew racing a logout hits a row that is already gone. */
    @Test
    void updatesOnMissingSessionAreNoOps() {
        assertThat(store.updateLastAccessedAt("gone", T0)).isFalse();
        assertThat(store.updateExpiresAt("gone", T0, T0)).isFalse();

        assertThat(store.findBySessionId("gone")).isEmpty();
    }

    @Test
    void deleteBySessionIdRemovesOnlyThatSession() {
        store.insert(session("sid-1", "user-1", T0.plusSeconds(3600)));
        store.insert(session("sid-2", "user-1", T0.plusSeconds(3600)));

        store.deleteBySessionId("sid-1");

        assertThat(store.findBySessionId("sid-1")).isEmpty();
        assertThat(store.findBySessionId("sid-2")).isPresent();
    }

    @Test
    void deleteByUserIdRemovesEverySessionOfThatUser() {
        store.insert(session("sid-1", "user-1", T0.plusSeconds(3600)));
        store.insert(session("sid-2", "user-1", T0.plusSeconds(3600)));
        store.insert(session("sid-3", "user-2", T0.plusSeconds(3600)));

        store.deleteByUserId("user-1");

        assertThat(store.findBySessionId("sid-1")).isEmpty();
        assertThat(store.findBySessionId("sid-2")).isEmpty();
        assertThat(store.findBySessionId("sid-3")).isPresent();
    }

    @Test
    void deleteExpiredRemovesOnlySessionsPastTheCutoff() {
        store.insert(session("old", "user-1", T0.minusSeconds(1)));
        store.insert(session("live", "user-1", T0.plusSeconds(1)));

        store.deleteExpired(T0);

        assertThat(store.findBySessionId("old")).isEmpty();
        assertThat(store.findBySessionId("live")).isPresent();
    }

    @Test
    void startupCreatesAMissingTableWithItsIndexes() {
        EmbeddedDatabase empty = emptyDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(empty);
        JdbcSentretSessionStore fresh = new JdbcSentretSessionStore(jdbc);
        ListAppender<ILoggingEvent> appender = captureLogs();

        try {
            fresh.afterSingletonsInstantiated();
            fresh.insert(session("sid-1", "user-1", T0.plusSeconds(3600)));

            assertThat(fresh.findBySessionId("sid-1")).isPresent();
            assertThat(jdbc.queryForList(
                    "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'SENTRET_SESSIONS'", String.class))
                    .contains("IDX_SENTRET_SESSIONS_USER_ID", "IDX_SENTRET_SESSIONS_EXPIRES_AT");
        } finally {
            releaseLogs(appender);
            empty.shutdown();
        }

        assertThat(appender.list)
                .anyMatch(event -> event.getLevel() == Level.INFO && event.getFormattedMessage().contains("Created table sentret_sessions"));
    }

    /** A second instance (or a restart) finds the table and leaves it alone. */
    @Test
    void startupCreatesTheTableOnlyOnce() {
        EmbeddedDatabase empty = emptyDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(empty);

        try {
            new JdbcSentretSessionStore(jdbc).afterSingletonsInstantiated();
            new JdbcSentretSessionStore(jdbc).insert(session("sid-1", "user-1", T0.plusSeconds(3600)));
            new JdbcSentretSessionStore(jdbc).afterSingletonsInstantiated();

            assertThat(new JdbcSentretSessionStore(jdbc).findBySessionId("sid-1")).isPresent();
        } finally {
            empty.shutdown();
        }
    }

    /** With create-table=false (schema owned by Flyway/Liquibase) a missing table is only reported. */
    @Test
    void startupOnlyReportsAMissingTableWhenCreationIsOff() {
        EmbeddedDatabase empty = emptyDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(empty);
        ListAppender<ILoggingEvent> appender = captureLogs();

        try {
            new JdbcSentretSessionStore(jdbc, false).afterSingletonsInstantiated();

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SENTRET_SESSIONS'", Integer.class))
                    .isZero();
        } finally {
            releaseLogs(appender);
            empty.shutdown();
        }

        assertThat(appender.list)
                .anyMatch(event -> event.getLevel() == Level.ERROR && event.getFormattedMessage().contains("sentret_sessions"));
    }

    /** A database user without CREATE TABLE gets an ERROR, never a failed startup. */
    @Test
    void startupReportsATableItCannotCreate() {
        EmbeddedDatabase empty = emptyDatabase();
        JdbcTemplate admin = new JdbcTemplate(empty);
        ListAppender<ILoggingEvent> appender = captureLogs();

        try {
            admin.execute("CREATE USER reader PASSWORD 'reader'");
            String url = admin.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
            JdbcTemplate reader = new JdbcTemplate(new DriverManagerDataSource(url, "reader", "reader"));

            new JdbcSentretSessionStore(reader).afterSingletonsInstantiated();
        } finally {
            releaseLogs(appender);
            empty.shutdown();
        }

        assertThat(appender.list)
                .anyMatch(event -> event.getLevel() == Level.ERROR && event.getFormattedMessage().contains("Could not create table"));
    }

    @Test
    void createTableSqlKeepsTheIdCaseSensitiveOnEveryDatabase() {
        assertThat(JdbcSentretSessionStore.createTableSql("MySQL"))
                .contains("session_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY");
        assertThat(JdbcSentretSessionStore.createTableSql("MariaDB"))
                .contains("session_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY");
        assertThat(JdbcSentretSessionStore.createTableSql("Microsoft SQL Server"))
                .contains("session_id VARCHAR(20) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY");
        assertThat(JdbcSentretSessionStore.createTableSql("PostgreSQL"))
                .contains("session_id VARCHAR(20) NOT NULL PRIMARY KEY");
        assertThat(JdbcSentretSessionStore.createTableSql("H2"))
                .contains("session_id VARCHAR(20) NOT NULL PRIMARY KEY");
    }

    @Test
    void startupIsQuietWhenTheTableExists() {
        ListAppender<ILoggingEvent> appender = captureLogs();

        try {
            store.afterSingletonsInstantiated();
        } finally {
            releaseLogs(appender);
        }

        assertThat(appender.list).isEmpty();
    }

    private static EmbeddedDatabase emptyDatabase() {
        return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(JdbcSentretSessionStore.class)).addAppender(appender);
        return appender;
    }

    private static void releaseLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(JdbcSentretSessionStore.class)).detachAppender(appender);
    }
}
