package io.github.sidneyroberto9.sentret.security;

/**
 * Immutable authenticated principal exposed by the library. Populated by the
 * {@link SentretAuthenticationFilter}. Authorization data (roles, permissions) belongs to the host
 * application, looked up by {@link #userId()}.
 */
public record SentretUser(String userId, String email, String sessionId) {
}
