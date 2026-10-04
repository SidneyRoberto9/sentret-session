package io.github.sidneyroberto9.sentret.security;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * Immutable authenticated principal exposed by the library. Populated by the
 * {@link SentretAuthenticationFilter} from the row it just validated, so the two deadlines are
 * available without another read. Authorization data (roles, permissions) belongs to the host
 * application, looked up by {@link #userId()}.
 *
 * <p>{@link #sessionId()} is the value of the HttpOnly cookie, so it is never serialized: returning
 * this record from an endpoint does not hand it to JavaScript.
 */
public record SentretUser(
        String userId,
        String email,
        @JsonIgnore String sessionId,
        Instant expiresAt,
        Instant lastAccessedAt
) {
}
