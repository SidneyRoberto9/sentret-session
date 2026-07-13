package io.github.sidneyroberto9.spring_session_lite.web.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory registry of live {@link SseEmitter}s per {@code userId}. Pure map management only —
 * add/remove/list — it never calls {@code SseEmitter#send}; that's the sole responsibility of
 * {@link SessionEventBroadcaster}, which uses this registry to look up who to push to.
 *
 * <p>Single-instance only. A multi-instance hub needs a pub/sub-backed
 * {@link SessionEventBroadcaster} implementation instead (see the plan's "Riscos" section on
 * horizontal scaling) — out of scope for this task.
 */
public class SpringSessionLiteSseRegistry {

    private final Map<String, List<SseEmitter>> emittersByUserId = new ConcurrentHashMap<>();

    public void add(String userId, SseEmitter emitter) {
        emittersByUserId.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(emitter);
    }

    public void remove(String userId, SseEmitter emitter) {
        emittersByUserId.computeIfPresent(userId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    /**
     * Live emitters currently registered for {@code userId}, oldest-first. Empty (never
     * {@code null}) when no emitter is connected for that user.
     */
    public List<SseEmitter> emittersFor(String userId) {
        return emittersByUserId.getOrDefault(userId, List.of());
    }

    /** Snapshot of every userId with at least one live emitter, for keep-alive fan-out. */
    public Set<String> connectedUserIds() {
        return Set.copyOf(emittersByUserId.keySet());
    }
}
