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
    public void logout(String userId, SpringSessionLiteSseLogoutEvent event) {
        broadcast(userId, EVENT_LOGOUT, event);
    }

    @Override
    public void renew(String userId, SpringSessionLiteSseRenewEvent event) {
        broadcast(userId, EVENT_RENEW, event);
    }

    @Override
    public void warning(String userId, SpringSessionLiteSseWarningEvent event) {
        broadcast(userId, EVENT_WARNING, event);
    }

    @Override
    public void pingAll() {
        for (String userId : registry.connectedUserIds()) {
            for (SseEmitter emitter : registry.emittersFor(userId)) {
                sendOrCleanup(userId, emitter, () -> emitter.send(SseEmitter.event().comment("ping")));
            }
        }
    }

    private void broadcast(String userId, String eventName, Object payload) {
        for (SseEmitter emitter : registry.emittersFor(userId)) {
            sendOrCleanup(userId, emitter, () -> emitter.send(SseEmitter.event().name(eventName).data(payload)));
        }
    }

    private void sendOrCleanup(String userId, SseEmitter emitter, SseSend action) {
        try {
            action.send();
        } catch (IOException | IllegalStateException ex) {
            log.debug("Removing dead SSE emitter for userId {}: {}", userId, ex.toString());
            registry.remove(userId, emitter);
        }
    }

    @FunctionalInterface
    private interface SseSend {
        void send() throws IOException;
    }
}
