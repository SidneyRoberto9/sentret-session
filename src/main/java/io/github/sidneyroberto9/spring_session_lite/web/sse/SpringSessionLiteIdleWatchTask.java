package io.github.sidneyroberto9.spring_session_lite.web.sse;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteSessionRemaining;
import io.github.sidneyroberto9.spring_session_lite.store.SpringSessionLiteSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

/**
 * Opt-in periodic sweep (active only when {@code spring-session-lite.sse-enabled=true}) that
 * evaluates every not-yet-absolute-expired session and, per session:
 *
 * <ul>
 *   <li>destroys it via {@link SpringSessionLiteService#logout(String)} when idle or absolute
 *       expiry has been crossed — reusing the existing method (rather than deleting via the store
 *       directly) so the same {@code SessionDestroyedEvent} listener that pushes {@code logout}
 *       for explicit {@code POST /session/logout} also covers sessions discovered here, without
 *       duplicating push logic;</li>
 *   <li>otherwise pushes a {@code warning} event, directly via {@link SessionEventBroadcaster},
 *       when within {@code warningBefore} of either deadline — there is no domain event for
 *       "about to expire", so this is the one case the task pushes itself. The warning is routed to
 *       that session's own emitters: the sweep evaluates one session row at a time, so the routing
 *       key and the payload's {@code sessionId} are the same value by construction.</li>
 * </ul>
 *
 * <p>This task's own candidate set ({@code store.findActive}, {@code expiresAt > now}) means it
 * can only ever observe absolute expiry being crossed in the narrow window between two ticks —
 * once a row's {@code expiresAt} is truly in the past, a later tick's query excludes it. Absolute
 * expiry cleanup at scale remains the job of the pre-existing {@code cleanup-enabled} task
 * ({@code SpringSessionLiteCleanupTask}); that path does not push an SSE event today, so a
 * connected client whose session is swept there (rather than caught here first) is not notified —
 * a known, accepted limitation of keeping this sweep simple (see the plan's single-instance-hub
 * assumption for this phase).
 *
 * <p>Cadence defaults to 10 seconds and is configurable via
 * {@code spring-session-lite.idle-watch-interval} (since 2.3.0). The sweep shares the host
 * application's {@code TaskScheduler}, whose default pool is one thread — on a hub with many live
 * sessions, a slow sweep is a slow sweep for every other {@code @Scheduled} bean in that
 * application, so raise {@code spring.task.scheduling.pool.size} accordingly.
 *
 * <p>Every tick also sends a keep-alive ping to all connected emitters (see
 * {@link SessionEventBroadcaster#pingAll()}), piggy-backing on this task's own timer instead of
 * introducing a second scheduled component just for that. No send this task triggers — ping,
 * warning, or the logout the destroy path publishes — runs on this thread: the broadcaster
 * dispatches all of them to its own queues, because a blocking write to a wedged socket must never
 * stall the sweep (see {@link InMemorySessionEventBroadcaster}).
 */
@Slf4j
@RequiredArgsConstructor
public class SpringSessionLiteIdleWatchTask {

    private final SessionEventBroadcaster broadcaster;
    private final SpringSessionLiteSessionStore store;
    private final SpringSessionLiteProperties properties;
    private final SpringSessionLiteService sessionService;

    public void evaluate() {
        long warningBeforeMs = properties.getWarningBefore().toMillis();

        for (SpringSessionLiteSession session : store.findActive(Instant.now())) {
            evaluateSession(session, warningBeforeMs);
        }

        broadcaster.pingAll();
    }

    /**
     * Two-step on purpose: <em>triage</em> from the row {@code findActive()} already loaded, then
     * <em>re-read</em> before acting on it.
     *
     * <p>Until 2.3.0 the triage step was itself a {@code sessionService.remaining(sessionId)} call —
     * one extra read-only transaction per session per tick, N+1 against the connection pool for
     * data already in hand ({@code expiresAt} and {@code lastAccessedAt} are on the loaded row).
     * The vast majority of live sessions are nowhere near either deadline, so they are now decided
     * entirely in memory and cost nothing beyond the single {@code findActive()} query.
     *
     * <p>The re-read is not the N+1 coming back: it runs only for the few sessions the triage says
     * need a warning or a destroy. It is required for correctness — {@code findActive()} snapshots
     * every row at the top of the sweep, and a sweep over many sessions takes long enough for a
     * {@code POST /session/heartbeat} (or an explicit logout) to land while it is still running.
     * Acting on the stale snapshot would log out a user who moved their mouse mid-sweep, or push an
     * inactivity warning for a session that no longer exists.
     */
    private void evaluateSession(SpringSessionLiteSession session, long warningBeforeMs) {
        String sessionId = session.getSessionId();

        if (!needsAction(sessionService.remainingOf(session), warningBeforeMs)) {
            return;
        }

        Optional<SpringSessionLiteSessionRemaining> current = sessionService.remaining(sessionId);

        if (current.isEmpty()) {
            // Gone already (race with a concurrent logout/expiry) — nothing to warn about.
            return;
        }

        act(sessionId, current.get(), warningBeforeMs);
    }

    /**
     * Whether the session is close enough to either deadline to be worth a fresh read. Expiry needs
     * no separate check: remaining time is clamped at zero, and {@code warningBefore} is never
     * negative, so an expired session is always inside the window.
     */
    private boolean needsAction(SpringSessionLiteSessionRemaining remaining, long warningBeforeMs) {
        Long idleRemainingMs = remaining.idleRemainingMs();

        return remaining.absoluteRemainingMs() <= warningBeforeMs || (idleRemainingMs != null && idleRemainingMs <= warningBeforeMs);
    }

    private void act(String sessionId, SpringSessionLiteSessionRemaining remaining, long warningBeforeMs) {
        Long idleRemainingMs = remaining.idleRemainingMs();
        long absoluteRemainingMs = remaining.absoluteRemainingMs();

        boolean idleExpired = idleRemainingMs != null && idleRemainingMs <= 0;
        boolean absoluteExpired = absoluteRemainingMs <= 0;

        if (idleExpired || absoluteExpired) {
            sessionService.logout(sessionId);
            log.debug("Idle-watch destroyed session {} (idleExpired={}, absoluteExpired={})", sessionId, idleExpired, absoluteExpired);
            return;
        }

        boolean idleWarning = idleRemainingMs != null && idleRemainingMs <= warningBeforeMs;
        boolean absoluteWarning = absoluteRemainingMs <= warningBeforeMs;

        if (!idleWarning && !absoluteWarning) {
            // The re-read moved the session back out of the warning window: a heartbeat landed
            // between findActive() and here. The user is active; say nothing.
            return;
        }

        boolean idleIsNearer = idleWarning && (!absoluteWarning || idleRemainingMs <= absoluteRemainingMs);
        long remainingMs = idleIsNearer ? idleRemainingMs : absoluteRemainingMs;
        String cause = idleIsNearer ? "idle" : "absolute";

        broadcaster.sendWarning(sessionId, new SpringSessionLiteSseWarningEvent(sessionId, remainingMs, absoluteRemainingMs, idleRemainingMs, cause));
    }
}
