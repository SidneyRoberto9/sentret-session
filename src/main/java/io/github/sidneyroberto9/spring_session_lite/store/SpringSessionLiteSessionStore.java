package io.github.sidneyroberto9.spring_session_lite.store;

import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;

import java.time.Instant;
import java.util.List;
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

    /**
     * Sessions not yet expired at {@code now} ({@code expiresAt > now}), for the opt-in idle-watch
     * sweep ({@code SpringSessionLiteIdleWatchTask}, active only when
     * {@code spring-session-lite.sse-enabled=true}) to evaluate for idle/absolute expiry and
     * upcoming-warning windows.
     *
     * <p>Declared as a {@code default} method — rather than an abstract one — so existing custom
     * store implementations (see the class javadoc) keep compiling across this minor release. The
     * default throws {@link UnsupportedOperationException}; override it in a custom store to
     * support the idle-watch/SSE feature, or leave {@code sse-enabled} off if the custom store does
     * not need it.
     */
    default List<SpringSessionLiteSession> findActive(Instant now) {
        throw new UnsupportedOperationException(
                "findActive(Instant) is not implemented by this SpringSessionLiteSessionStore. "
                        + "Override it to support spring-session-lite.sse-enabled=true (idle-watch/SSE), "
                        + "or keep that property disabled if this custom store does not need it.");
    }
}
