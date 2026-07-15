package io.github.sidneyroberto9.spring_session_lite.web.sse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * Default, single-instance {@link SessionEventBroadcaster}. This is the only class in the SSE
 * stack that calls {@code SseEmitter#send} — everything else (controller, idle-watch task, the
 * domain-event listener) goes through the interface above.
 *
 * <p>A dead emitter (client disconnected without the completion/timeout/error callbacks having
 * fired yet, or any other {@link IOException}/{@link IllegalStateException} on send) is removed
 * from the registry right here as a defensive backstop, in addition to the cleanup wired in
 * {@code SpringSessionLiteSseController}.
 */
@Slf4j
@RequiredArgsConstructor
public class InMemorySessionEventBroadcaster implements SessionEventBroadcaster {

    private static final String EVENT_LOGOUT = "logout";
    private static final String EVENT_RENEW = "renew";
    private static final String EVENT_WARNING = "warning";

    private final SpringSessionLiteSseRegistry registry;

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
                sendOrCleanup(sessionId, emitter, () -> emitter.send(SseEmitter.event().comment("ping")));
            }
        }
    }

    private void broadcast(String sessionId, String eventName, Object payload) {
        for (SseEmitter emitter : registry.emittersForSession(sessionId)) {
            sendOrCleanup(sessionId, emitter, () -> emitter.send(SseEmitter.event().name(eventName).data(payload)));
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
