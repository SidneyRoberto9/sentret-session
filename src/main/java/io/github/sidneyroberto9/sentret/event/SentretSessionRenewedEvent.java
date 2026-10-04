package io.github.sidneyroberto9.sentret.event;

import java.time.Instant;

/**
 * Published after a session's absolute expiry is reset (renewal).
 */
public record SentretSessionRenewedEvent(String userId, String sessionId, Instant renewedAt) {
}
