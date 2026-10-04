package io.github.sidneyroberto9.sentret.event;

import java.time.Instant;

/**
 * Published after a session's absolute expiry is reset (renewal).
 *
 * <p>Carries the remaining-time snapshot as of the renewal ({@code null} on both when published by
 * the pre-2.3.0 three-arg constructor). The publisher has just loaded and saved the row, so
 * computing it there costs nothing; without it every listener that wants to report the new
 * deadlines — the SSE bridge does — has to re-read the row the publisher was holding one frame
 * earlier.
 */
public record SentretSessionRenewedEvent(
        String userId,
        String sessionId,
        Instant renewedAt,
        Long absoluteRemainingMs,
        Long idleRemainingMs
) {

    /**
     * Pre-2.3.0 shape, kept so existing publishers/tests still compile. Listeners must treat the
     * remaining-time snapshot as absent and fall back to a read.
     */
    public SentretSessionRenewedEvent(String userId, String sessionId, Instant renewedAt) {
        this(userId, sessionId, renewedAt, null, null);
    }
}
