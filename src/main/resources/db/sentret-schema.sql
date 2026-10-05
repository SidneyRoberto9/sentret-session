-- Sentret — the library creates this table at startup when missing (sentret.create-table=true, the default).
-- With sentret.create-table=false run it once yourself (Flyway, Liquibase or by hand) before starting the application.
-- Portable as written: MySQL, MariaDB, PostgreSQL, SQL Server and H2.
-- Times are epoch milliseconds (BIGINT): no time-zone conversion, no 2038 limit.
--
-- session_id is case-sensitive. MySQL, MariaDB and SQL Server ignore case by default; give the
-- column a binary collation there so lookups and the primary key keep all 120 bits:
--   MySQL / MariaDB: session_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
--   SQL Server:      session_id VARCHAR(20) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY,
-- PostgreSQL and H2 already compare case-sensitively.

CREATE TABLE sentret_sessions (
    session_id       VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id          VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    created_at       BIGINT       NOT NULL,
    expires_at       BIGINT       NOT NULL,
    last_accessed_at BIGINT       NOT NULL
);

CREATE INDEX idx_sentret_sessions_user_id ON sentret_sessions (user_id);
CREATE INDEX idx_sentret_sessions_expires_at ON sentret_sessions (expires_at);
