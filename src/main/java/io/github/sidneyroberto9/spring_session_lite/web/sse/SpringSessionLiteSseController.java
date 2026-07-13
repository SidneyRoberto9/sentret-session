package io.github.sidneyroberto9.spring_session_lite.web.sse;

import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.web.SpringSessionLiteCurrentSession;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Opt-in {@code GET <endpoints-base-path>/stream} SSE endpoint (base path configurable via
 * {@code spring-session-lite.endpoints-base-path}, the same property Task 2 introduced for the
 * REST endpoints), registered only when {@code spring-session-lite.sse-enabled=true} (see
 * {@code SpringSessionLiteSseAutoConfiguration}).
 *
 * <p>Requires authentication: this path is not in {@code permitAllPaths}, and the default security
 * chain's {@code anyRequest().authenticated()} already protects any path not explicitly listed
 * there — no change to the security chain was needed for this endpoint.
 *
 * <p>The emitter is registered under the caller's {@code userId} in
 * {@link SpringSessionLiteSseRegistry}. This class only manages the HTTP/emitter lifecycle
 * (creation, timeout, cleanup callbacks) — it never calls {@code SseEmitter#send}; all event
 * emission goes through {@link SessionEventBroadcaster} instead (the idle-watch task's periodic
 * ping keeps the connection alive; see {@code SpringSessionLiteIdleWatchTask}).
 */
@RestController
@RequestMapping("${spring-session-lite.endpoints-base-path:/session}")
@RequiredArgsConstructor
public class SpringSessionLiteSseController {

    /**
     * A timeout of zero (or less) means "no timeout" in the underlying Servlet async contract —
     * the connection is expected to be long-lived, kept alive by the idle-watch task's periodic
     * ping, and closed by the client (or an intermediary) rather than by a fixed deadline.
     */
    private static final long NO_TIMEOUT = 0L;

    private final SpringSessionLiteSseRegistry registry;

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@SpringSessionLiteCurrentSession SpringSessionLiteUser user, HttpServletResponse response) {
        // Prevents reverse proxies (nginx and friends) from buffering the stream, which would
        // otherwise delay/batch events instead of pushing them as they're emitted.
        response.setHeader("X-Accel-Buffering", "no");

        String userId = user.userId();
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);

        registry.add(userId, emitter);

        emitter.onCompletion(() -> registry.remove(userId, emitter));
        emitter.onTimeout(() -> registry.remove(userId, emitter));
        emitter.onError(ex -> registry.remove(userId, emitter));

        return emitter;
    }
}
