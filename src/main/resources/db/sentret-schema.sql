-- Sentret — run once (Flyway, Liquibase or by hand) before starting the application.
-- Portable as written: MySQL, MariaDB, PostgreSQL, SQL Server and H2.
-- Times are epoch milliseconds (BIGINT): no time-zone conversion, no 2038 limit.

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
