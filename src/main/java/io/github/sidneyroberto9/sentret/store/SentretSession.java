package io.github.sidneyroberto9.sentret.store;

import java.time.Instant;

/**
 * One persisted session row. Immutable: changes go through the targeted update methods of
 * {@link SentretSessionStore}, never through a read-modify-write of the whole row.
 */
public record SentretSession(
        String sessionId,
        String userId,
        String email,
        Instant createdAt,
        Instant expiresAt,
        Instant lastAccessedAt
) {
}
