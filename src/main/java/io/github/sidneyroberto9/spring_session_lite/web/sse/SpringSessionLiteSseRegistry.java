package io.github.sidneyroberto9.spring_session_lite.web.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory registry of live {@link SseEmitter}s per {@code sessionId}. Pure map management only —
 * add/remove/list — it never calls {@code SseEmitter#send}; that's the sole responsibility of
 * {@link SessionEventBroadcaster}, which uses this registry to look up who to push to.
 *
 * <p><strong>The key is the sessionId, never the userId.</strong> One user with N live sessions
 * (two devices, two browsers, or a session row orphaned by a double-login) occupies N independent
 * keys. This is load-bearing, not cosmetic: every SSE event describes exactly one session, and
 * until 2.2.0 this map was keyed by userId, so an event about session A was delivered to a tab
 * authenticated by session B. That tab had no way to reject it — the payload names a sessionId, but
 * a browser cannot learn its own (it is not exposed by {@code GET /session/status}, and exposing it
 * would defeat the httpOnly cookie). The result was an inactivity warning for someone else's
 * session, and — when the other session expired — a {@code logout} that terminated a user who was
 * actively working. Only the server can answer "is this event mine?"; that answer is this key.
 *
 * <p>Tabs sharing one session cookie share one key, which is exactly right: they are one session,
 * and a logout does apply to all of them.
 *
 * <p>Single-instance only. A multi-instance hub needs a pub/sub-backed
 * {@link SessionEventBroadcaster} implementation instead (see the plan's "Riscos" section on
 * horizontal scaling) — out of scope for this task.
 */
public class SpringSessionLiteSseRegistry {

    private final Map<String, List<SseEmitter>> emittersBySessionId = new ConcurrentHashMap<>();

    public void add(String sessionId, SseEmitter emitter) {
        emittersBySessionId.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(emitter);
    }

    public void remove(String sessionId, SseEmitter emitter) {
        emittersBySessionId.computeIfPresent(sessionId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    /**
     * Live emitters currently registered for {@code sessionId}, oldest-first. Empty (never
     * {@code null}) when no emitter is connected for that session.
     */
    public List<SseEmitter> emittersForSession(String sessionId) {
        return emittersBySessionId.getOrDefault(sessionId, List.of());
    }

    /** Snapshot of every sessionId with at least one live emitter, for keep-alive fan-out. */
    public Set<String> connectedSessionIds() {
        return Set.copyOf(emittersBySessionId.keySet());
    }
}
