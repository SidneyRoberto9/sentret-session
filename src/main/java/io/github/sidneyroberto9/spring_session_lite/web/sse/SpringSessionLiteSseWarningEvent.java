package io.github.sidneyroberto9.spring_session_lite.web.sse;

/**
 * Payload of the SSE {@code warning} event: the session identified by {@code sessionId} is within
 * {@code spring-session-lite.warning-before} of idle or absolute expiry, as evaluated by the
 * idle-watch sweep.
 *
 * <p>{@code remainingMs} is the nearer of the two deadlines — the one {@code cause} refers to —
 * for clients that just want a single countdown; {@code absoluteRemainingMs}/
 * {@code idleRemainingMs} are also included for clients that want both. {@code idleRemainingMs}
 * is {@code null} when idle enforcement is disabled. {@code cause} is {@code "idle"} or
 * {@code "absolute"}.
 *
 * <p>The idle-watch sweep re-evaluates on every tick and does not de-duplicate: a session sitting
 * inside the warning window is pushed again on every tick until it's renewed or destroyed. Clients
 * should treat this event as idempotent (safe to receive repeatedly with a shrinking
 * {@code remainingMs}).
 */
public record SpringSessionLiteSseWarningEvent(
        String sessionId,
        long remainingMs,
        long absoluteRemainingMs,
        Long idleRemainingMs,
        String cause
) {
}
