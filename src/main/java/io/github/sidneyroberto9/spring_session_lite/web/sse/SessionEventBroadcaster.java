package io.github.sidneyroberto9.spring_session_lite.web.sse;

/**
 * Abstraction over "push an SSE event to every live connection of a session". All SSE emission in
 * this library flows through this interface — the controller and the idle-watch task never call
 * {@code SseEmitter#send} directly, only these methods.
 *
 * <p><strong>Contract:</strong> the {@code sessionId} argument is a session identifier, never a
 * userId. An implementation must deliver only to connections authenticated by that exact session.
 * User-wide fan-out is explicitly not part of this contract: each event describes exactly one
 * session, and a peer session of the same user cannot tell the event is not its own (a browser
 * cannot learn its own sessionId). Delivering wider than the session logs out users who are
 * actively working — see {@link SpringSessionLiteSseRegistry}.
 *
 * <p>{@link InMemorySessionEventBroadcaster} is the single-instance default implementation,
 * backed by {@link SpringSessionLiteSseRegistry}. This interface is the extension point a
 * horizontal (multi-instance, pub/sub-backed — e.g. Redis) hub would implement instead, so that a
 * push originating on one instance reaches emitters connected to another; that implementation is
 * out of scope for this task (see the plan's "Riscos" section on horizontal scaling) — only this
 * seam needs to exist.
 */
public interface SessionEventBroadcaster extends AutoCloseable {

    /**
     * Pushes {@code event: logout} to every emitter registered for {@code sessionId}.
     *
     * @since 2.2.0 replaces {@code logout(String userId, ...)}, which routed by user.
     */
    void sendLogout(String sessionId, SpringSessionLiteSseLogoutEvent event);

    /**
     * Pushes {@code event: renew} to every emitter registered for {@code sessionId}.
     *
     * @since 2.2.0 replaces {@code renew(String userId, ...)}, which routed by user.
     */
    void sendRenew(String sessionId, SpringSessionLiteSseRenewEvent event);

    /**
     * Pushes {@code event: warning} to every emitter registered for {@code sessionId}.
     *
     * @since 2.2.0 replaces {@code warning(String userId, ...)}, which routed by user.
     */
    void sendWarning(String sessionId, SpringSessionLiteSseWarningEvent event);

    /**
     * Sends a keep-alive SSE comment (invisible to {@code EventSource}'s event API, exists only to
     * keep intermediary proxies/load balancers from closing an idle connection) to every
     * currently-connected emitter, across every connected session. Deliberately session-agnostic:
     * keep-alive is a transport concern, so no routing key belongs in it.
     */
    void pingAll();

    /**
     * Releases whatever an implementation holds for pushing (threads, connections, subscriptions).
     * Declared here, and not only on {@link InMemorySessionEventBroadcaster}, because Spring infers
     * the destroy method from the <em>registered bean's</em> class: a consumer who wraps or replaces
     * the default broadcaster (the documented extension seam — see this interface's javadoc) would
     * otherwise leak the wrapped instance's threads on every context shutdown.
     *
     * <p>Default no-op, so a stateless implementation ignores it. <strong>A decorator must override
     * it and delegate</strong> to whatever it wraps.
     *
     * @since 2.3.0
     */
    @Override
    default void close() {
    }
}
