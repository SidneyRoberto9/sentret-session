package io.github.sidneyroberto9.spring_session_lite.web.sse;

import io.github.sidneyroberto9.spring_session_lite.event.SpringSessionLiteSessionDestroyedEvent;
import io.github.sidneyroberto9.spring_session_lite.event.SpringSessionLiteSessionRenewedEvent;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteSessionRemaining;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;

import java.util.Optional;

/**
 * Bridges core domain events to the SSE push channel, pushing {@code logout}/{@code renew}
 * immediately — not just on the idle-watch sweep's next tick — whenever a session is destroyed or
 * renewed by any means: explicit {@code POST /session/logout|renew}, the idle-watch sweep itself
 * (which destroys expired sessions via {@code SpringSessionLiteService#logout(String)}, publishing
 * the same event), or any future caller. One listener covers every current and future path,
 * without the emitting code needing to know SSE exists.
 */
@RequiredArgsConstructor
public class SpringSessionLiteSseSessionEventListener {

    private final SessionEventBroadcaster broadcaster;
    private final SpringSessionLiteService sessionService;

    @EventListener
    public void onSessionDestroyed(SpringSessionLiteSessionDestroyedEvent event) {
        broadcaster.sendLogout(event.sessionId(), new SpringSessionLiteSseLogoutEvent(event.sessionId()));
    }

    @EventListener
    public void onSessionRenewed(SpringSessionLiteSessionRenewedEvent event) {
        Optional<SpringSessionLiteSessionRemaining> remaining = sessionService.remaining(event.sessionId());

        long absoluteRemainingMs = remaining.map(SpringSessionLiteSessionRemaining::absoluteRemainingMs).orElse(0L);
        Long idleRemainingMs = remaining.map(SpringSessionLiteSessionRemaining::idleRemainingMs).orElse(null);

        broadcaster.sendRenew(event.sessionId(), new SpringSessionLiteSseRenewEvent(event.sessionId(), absoluteRemainingMs, idleRemainingMs));
    }
}
