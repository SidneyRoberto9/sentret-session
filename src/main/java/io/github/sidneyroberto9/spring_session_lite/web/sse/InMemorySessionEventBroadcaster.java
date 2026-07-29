package io.github.sidneyroberto9.spring_session_lite.web.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Default, single-instance {@link SessionEventBroadcaster}. This is the only class in the SSE
 * stack that calls {@code SseEmitter#send} — everything else (controller, idle-watch task, the
 * domain-event listener) goes through the interface above.
 *
 * <p><strong>No send ever runs on the caller's thread.</strong> {@code SseEmitter#send} is a
 * blocking write on the container's output stream: a client whose TCP window is full, or whose
 * connection is half-open, blocks the writer until the socket times out — and a container
 * configured without a connection timeout never times it out at all. The callers are the idle-watch
 * sweep (on the host application's shared {@code TaskScheduler}, default pool size 1) and
 * {@code SpringSessionLiteSseSessionEventListener}, which Spring dispatches synchronously from
 * inside {@code SpringSessionLiteService}'s open transaction. Before 2.3.0 one wedged browser could
 * therefore freeze session expiry and every other {@code @Scheduled} bean in the application, and
 * pin an uncommitted transaction — and its JDBC connection — for as long as it stayed wedged.
 *
 * <p>A dead emitter (client disconnected without the completion/timeout/error callbacks having
 * fired yet, or any other {@link IOException}/{@link IllegalStateException} on send) is removed
 * from the registry right here as a defensive backstop, in addition to the cleanup wired in
 * {@code SpringSessionLiteSseController}.
 */
@Slf4j
public class InMemorySessionEventBroadcaster implements SessionEventBroadcaster {

    private static final String EVENT_LOGOUT = "logout";
    private static final String EVENT_RENEW = "renew";
    private static final String EVENT_WARNING = "warning";

    /**
     * Number of independent single-thread send queues.
     *
     * <p>Not one shared queue: a wedged client would block it forever and no session would receive
     * anything again. Not a thread per send either: a session's events must reach its emitters in
     * the order they were produced, or a tab gets {@code warning} after the {@code logout} that
     * ended the session it describes. A session is always routed to the same queue (hash of its
     * sessionId), so its events stay ordered, and a wedged client stalls only the sessions that
     * share its queue.
     *
     * <p>ponytail: fixed stripe count — one wedged client freezes pushes for the ~1/8 of sessions
     * hashing to its queue. Per-session queues if that ratio ever matters.
     */
    private static final int SEND_QUEUES = 8;

    /**
     * Per-queue backlog before events are dropped (logged at WARN). Bounded on purpose: a queue
     * whose thread is blocked on a dead socket must not grow without limit while the sweep keeps
     * ticking. Unlike the skip-if-in-flight flag this replaces, a full queue recovers on its own the
     * moment the wedged send returns or the emitter is torn down.
     */
    private static final int QUEUE_CAPACITY = 1_000;

    private final SpringSessionLiteSseRegistry registry;

    private final List<Executor> sendExecutors;

    private final boolean ownsExecutors;

    public InMemorySessionEventBroadcaster(SpringSessionLiteSseRegistry registry) {
        this.registry = registry;
        this.sendExecutors = defaultSendExecutors();
        this.ownsExecutors = true;
    }

    /**
     * Overload for callers that own the send thread — tests pass {@code Runnable::run} to keep every
     * push synchronous and deterministic.
     *
     * <p>The executor passed here is <strong>not</strong> shut down by {@link #close()}: it belongs
     * to the caller, who may be sharing it with unrelated work.
     *
     * @since 2.3.0
     */
    public InMemorySessionEventBroadcaster(SpringSessionLiteSseRegistry registry, Executor sendExecutor) {
        this.registry = registry;
        this.sendExecutors = List.of(sendExecutor);
        this.ownsExecutors = false;
    }

    private static List<Executor> defaultSendExecutors() {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("spring-session-lite-sse-send-");
        threadFactory.setDaemon(true);

        return IntStream.range(0, SEND_QUEUES)
                .<Executor>mapToObj(queue -> new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY), threadFactory))
                .toList();
    }

    @Override
    public void sendLogout(String sessionId, SpringSessionLiteSseLogoutEvent event) {
        broadcast(sessionId, EVENT_LOGOUT, event);
    }

    @Override
    public void sendRenew(String sessionId, SpringSessionLiteSseRenewEvent event) {
        broadcast(sessionId, EVENT_RENEW, event);
    }

    @Override
    public void sendWarning(String sessionId, SpringSessionLiteSseWarningEvent event) {
        broadcast(sessionId, EVENT_WARNING, event);
    }

    @Override
    public void pingAll() {
        for (String sessionId : registry.connectedSessionIds()) {
            for (SseEmitter emitter : registry.emittersForSession(sessionId)) {
                dispatch(sessionId, emitter, () -> emitter.send(SseEmitter.event().comment("ping")));
            }
        }
    }

    /**
     * Shuts down the send queues this instance created. A no-op when the executor was supplied by
     * the caller (see the two-arg constructor).
     */
    @Override
    public void close() {
        if (!ownsExecutors) {
            return;
        }

        for (Executor executor : sendExecutors) {
            ((ExecutorService) executor).shutdownNow();
        }
    }

    private void broadcast(String sessionId, String eventName, Object payload) {
        for (SseEmitter emitter : registry.emittersForSession(sessionId)) {
            dispatch(sessionId, emitter, () -> emitter.send(SseEmitter.event().name(eventName).data(payload)));
        }
    }

    private void dispatch(String sessionId, SseEmitter emitter, SseSend action) {
        Executor executor = sendExecutors.get(Math.floorMod(sessionId.hashCode(), sendExecutors.size()));

        try {
            executor.execute(() -> sendOrCleanup(sessionId, emitter, action));
        } catch (RejectedExecutionException ex) {
            log.warn("Dropping SSE event for sessionId {}: send queue is full or already shut down ({})", sessionId, ex.toString());
        }
    }

    private void sendOrCleanup(String sessionId, SseEmitter emitter, SseSend action) {
        try {
            action.send();
        } catch (IOException | IllegalStateException ex) {
            log.debug("Removing dead SSE emitter for sessionId {}: {}", sessionId, ex.toString());
            registry.remove(sessionId, emitter);
        }
    }

    @FunctionalInterface
    private interface SseSend {
        void send() throws IOException;
    }
}
