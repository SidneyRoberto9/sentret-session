package io.github.sidneyroberto9.spring_session_lite.event;

import java.time.Instant;

/**
 * Published after a session's absolute expiry is reset (renewal).
 */
public record SpringSessionLiteSessionRenewedEvent(String userId, String sessionId, Instant renewedAt) {
}
