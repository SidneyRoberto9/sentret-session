package io.github.sidneyroberto9.sentret.event;

/**
 * Published after a session is destroyed (logout). Carries {@code userId} so listeners (auditing,
 * metrics) do not need to look the session up after it is gone. It describes exactly one session:
 * never use {@code userId} to act on the user's other sessions.
 */
public record SentretSessionDestroyedEvent(String userId, String sessionId) {
}
