package io.github.sidneyroberto9.spring_session_lite.event;

/**
 * Published after a session is destroyed (logout or single-session revocation). Carries
 * {@code userId} (in addition to {@code sessionId}, mirroring
 * {@link SpringSessionLiteSessionRenewedEvent}) so listeners that route by user — e.g. the SSE
 * push in {@code web.sse} — don't need to look the session back up after it's already gone.
 *
 * <p>A secondary, single-argument constructor is kept for source compatibility with callers built
 * against pre-2.1.0 versions of this library (where this record only had {@code sessionId}); it
 * defaults {@code userId} to {@code null}.
 */
public record SpringSessionLiteSessionDestroyedEvent(String userId, String sessionId) {

    public SpringSessionLiteSessionDestroyedEvent(String sessionId) {
        this(null, sessionId);
    }
}
