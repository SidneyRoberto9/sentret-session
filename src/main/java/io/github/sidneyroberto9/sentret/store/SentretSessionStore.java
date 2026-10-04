package io.github.sidneyroberto9.sentret.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage abstraction for sessions. The default implementation is {@link JdbcSentretSessionStore};
 * declare your own bean of this type to replace it.
 */
public interface SentretSessionStore {

    void insert(SentretSession session);

    Optional<SentretSession> findBySessionId(String sessionId);

    void updateLastAccessedAt(String sessionId, Instant lastAccessedAt);

    void updateExpiresAt(String sessionId, Instant expiresAt, Instant lastAccessedAt);

    void deleteBySessionId(String sessionId);

    void deleteByUserId(String userId);

    void deleteExpired(Instant now);
}
