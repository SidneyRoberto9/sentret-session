package io.github.sidneyroberto9.spring_session_lite.web.sse;

/**
 * Payload of the SSE {@code renew} event: the session identified by {@code sessionId} had its
 * absolute expiry reset. Mirrors the remaining-time shape of
 * {@code SpringSessionLiteSessionRemaining} so a connected client can refresh its countdown
 * directly from the push, without an extra round-trip to {@code GET /session/status}.
 *
 * <p>{@code idleRemainingMs} is {@code null} when idle enforcement ({@code maxIdle}) is disabled.
 */
public record SpringSessionLiteSseRenewEvent(String sessionId, long absoluteRemainingMs, Long idleRemainingMs) {
}
