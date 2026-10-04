package io.github.sidneyroberto9.sentret.event;

/**
 * Published after a session is destroyed (logout or single-session revocation). Carries
 * {@code userId} (in addition to {@code sessionId}, mirroring
 * {@link SentretSessionRenewedEvent}) so consumer-side listeners — auditing, metrics,
 * "who was this?" observability — don't need to look the session back up after it's already gone.
 *
 * <p><strong>Do not route delivery by {@code userId}.</strong> This event describes exactly one
 * session. The library's own SSE push ({@code web.sse}) routes by {@code sessionId}: until 2.2.0 it
 * routed by user, so destroying one session terminated every other tab of that user — including
 * sessions the user was actively working in. {@code userId} here is for identifying, not for
 * addressing.
 *
 * <p>A secondary, single-argument constructor is kept for source compatibility with callers built
 * against pre-2.1.0 versions of this library (where this record only had {@code sessionId}); it
 * defaults {@code userId} to {@code null} — another reason routing on it was unsound.
 */
public record SentretSessionDestroyedEvent(String userId, String sessionId) {

    public SentretSessionDestroyedEvent(String sessionId) {
        this(null, sessionId);
    }
}
