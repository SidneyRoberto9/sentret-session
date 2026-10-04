package io.github.sidneyroberto9.sentret.security;

import java.time.Instant;

/**
 * Immutable authenticated principal exposed by the library. Populated by the
 * {@link SentretAuthenticationFilter} from the row it just validated, so the two deadlines are
 * available without another read. Authorization data (roles, permissions) belongs to the host
 * application, looked up by {@link #userId()}.
 */
public record SentretUser(String userId, String email, String sessionId, Instant expiresAt, Instant lastAccessedAt) {
}
