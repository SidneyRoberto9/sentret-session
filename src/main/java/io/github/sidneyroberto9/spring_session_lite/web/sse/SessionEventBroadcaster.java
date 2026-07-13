package io.github.sidneyroberto9.spring_session_lite.web.sse;

/**
 * Abstraction over "push an SSE event to every live connection of a user". All SSE emission in
 * this library flows through this interface — the controller and the idle-watch task never call
 * {@code SseEmitter#send} directly, only these methods.
 *
 * <p>{@link InMemorySessionEventBroadcaster} is the single-instance default implementation,
 * backed by {@link SpringSessionLiteSseRegistry}. This interface is the extension point a
 * horizontal (multi-instance, pub/sub-backed — e.g. Redis) hub would implement instead, so that a
 * push originating on one instance reaches emitters connected to another; that implementation is
 * out of scope for this task (see the plan's "Riscos" section on horizontal scaling) — only this
 * seam needs to exist.
 */
public interface SessionEventBroadcaster {

    /** Pushes {@code event: logout} to every emitter registered for {@code userId}. */
    void logout(String userId, SpringSessionLiteSseLogoutEvent event);

    /** Pushes {@code event: renew} to every emitter registered for {@code userId}. */
    void renew(String userId, SpringSessionLiteSseRenewEvent event);

    /** Pushes {@code event: warning} to every emitter registered for {@code userId}. */
    void warning(String userId, SpringSessionLiteSseWarningEvent event);

    /**
     * Sends a keep-alive SSE comment (invisible to {@code EventSource}'s event API, exists only to
     * keep intermediary proxies/load balancers from closing an idle connection) to every
     * currently-connected emitter, across all users.
     */
    void pingAll();
}
