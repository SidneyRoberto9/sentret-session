package io.github.sidneyroberto9.spring_session_lite.web.sse;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteSessionRemaining;
import io.github.sidneyroberto9.spring_session_lite.store.SpringSessionLiteSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

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
 *       "about to expire", so this is the one case the task pushes itself.</li>
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
 * <p>Fixed 10-second cadence, not configurable — the plan does not call for a new property for
 * this interval, and the existing {@code warningBefore}/{@code maxIdle} windows are typically
 * measured in minutes, so a 10s granularity is more than adequate.
 *
 * <p>Every tick also sends a keep-alive ping to all connected emitters (see
 * {@link SessionEventBroadcaster#pingAll()}), piggy-backing on this task's own timer instead of
 * introducing a second scheduled component just for that.
 */
@Slf4j
@RequiredArgsConstructor
public class SpringSessionLiteIdleWatchTask {

    private static final long FIXED_DELAY_MS = 10_000L;

    private final SpringSessionLiteSessionStore store;
    private final SpringSessionLiteService sessionService;
    private final SpringSessionLiteProperties properties;
    private final SessionEventBroadcaster broadcaster;

    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void evaluate() {
        long warningBeforeMs = properties.getWarningBefore().toMillis();

        for (SpringSessionLiteSession session : store.findActive(Instant.now())) {
            evaluateSession(session, warningBeforeMs);
        }

        broadcaster.pingAll();
    }

    private void evaluateSession(SpringSessionLiteSession session, long warningBeforeMs) {
        String sessionId = session.getSessionId();
        String userId = session.getUserId();

        Optional<SpringSessionLiteSessionRemaining> remainingOpt = sessionService.remaining(sessionId);

        if (remainingOpt.isEmpty()) {
            // Gone already (race with a concurrent logout/expiry) — nothing left to evaluate.
            return;
        }

        SpringSessionLiteSessionRemaining remaining = remainingOpt.get();
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
            return;
        }

        boolean idleIsNearer = idleWarning && (!absoluteWarning || idleRemainingMs <= absoluteRemainingMs);
        long remainingMs = idleIsNearer ? idleRemainingMs : absoluteRemainingMs;
        String cause = idleIsNearer ? "idle" : "absolute";

        broadcaster.warning(userId, new SpringSessionLiteSseWarningEvent(sessionId, remainingMs, absoluteRemainingMs, idleRemainingMs, cause));
    }
}
