package io.github.sidneyroberto9.sentret.security;

import java.util.List;

/**
 * Immutable authenticated principal exposed by the library. Populated by the
 * {@link SentretAuthenticationFilter} and injectable via
 * {@code @SentretCurrentSession}.
 */
public record SentretUser(String userId, String email, String sessionId, List<String> roles) {

    public SentretUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public SentretUser(String userId, String email, String sessionId) {
        this(userId, email, sessionId, List.of());
    }
}
