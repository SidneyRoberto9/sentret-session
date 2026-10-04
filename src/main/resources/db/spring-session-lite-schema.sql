-- Spring Session Lite — DDL for apps using ddl-auto=none/validate
-- Run once in your database before starting the application.
-- Table and index names match the SentretSession entity exactly.

-- MySQL / MariaDB
CREATE TABLE spring_session_lite_sessions (
    id               VARCHAR(36)  NOT NULL,
    session_id       VARCHAR(32)  NOT NULL,
    user_id          VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    roles            VARCHAR(255),
    created_at       DATETIME(6),
    expires_at       DATETIME(6)  NOT NULL,
    last_accessed_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY idx_spring_session_lite_sessions_session_id (session_id),
    KEY idx_spring_session_lite_sessions_user_id (user_id),
    KEY idx_spring_session_lite_sessions_expires_at (expires_at)
);

-- PostgreSQL variant:
-- CREATE TABLE spring_session_lite_sessions (
--     id               VARCHAR(36)  NOT NULL PRIMARY KEY,
--     session_id       VARCHAR(32)  NOT NULL UNIQUE,
--     user_id          VARCHAR(255) NOT NULL,
--     email            VARCHAR(255),
--     roles            VARCHAR(255),
--     created_at       TIMESTAMP,
--     expires_at       TIMESTAMP    NOT NULL,
--     last_accessed_at TIMESTAMP
-- );
-- CREATE INDEX idx_spring_session_lite_sessions_user_id ON spring_session_lite_sessions (user_id);
-- CREATE INDEX idx_spring_session_lite_sessions_expires_at ON spring_session_lite_sessions (expires_at);

-- H2 (tests): same as the PostgreSQL variant above.
