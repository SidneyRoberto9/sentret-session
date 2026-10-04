package io.github.sidneyroberto9.sentret.event;

import java.time.Instant;

/**
 * Published after a session is successfully created (login).
 */
public record SentretSessionCreatedEvent(String userId, String sessionId, Instant createdAt) {
}
