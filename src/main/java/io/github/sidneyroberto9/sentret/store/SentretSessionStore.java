package io.github.sidneyroberto9.sentret.store;

import io.github.sidneyroberto9.sentret.domain.SentretSession;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage abstraction for sessions. The default implementation is JPA-backed
 * ({@link JpaSentretSessionStore}); provide your own bean to swap in Redis/Mongo/etc.
 */
public interface SentretSessionStore {

    SentretSession save(SentretSession session);

    Optional<SentretSession> findBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);

    void deleteByUserId(String userId);

    void deleteExpired(Instant now);

}
