package io.github.sidneyroberto9.spring_session_lite.store;

import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage abstraction for sessions. The default implementation is JPA-backed
 * ({@link JpaSpringSessionLiteSessionStore}); provide your own bean to swap in Redis/Mongo/etc.
 */
public interface SpringSessionLiteSessionStore {

    SpringSessionLiteSession save(SpringSessionLiteSession session);

    Optional<SpringSessionLiteSession> findBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);

    void deleteByUserId(String userId);

    void deleteExpired(Instant now);

}
