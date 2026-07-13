package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.event.SpringSessionLiteSessionDestroyedEvent;
import io.github.sidneyroberto9.spring_session_lite.event.SpringSessionLiteSessionRenewedEvent;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteCookieManager;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteIpHasher;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteIpResolver;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.store.SpringSessionLiteSessionStore;
import io.github.sidneyroberto9.spring_session_lite.web.sse.InMemorySessionEventBroadcaster;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteIdleWatchTask;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseLogoutEvent;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRegistry;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseSessionEventListener;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseWarningEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link SpringSessionLiteIdleWatchTask} end to end against real collaborators: a real
 * {@link SpringSessionLiteService} (only its {@link SpringSessionLiteSessionStore} dependency is
 * mocked, matching {@code SpringSessionLiteServiceTest}'s convention), a real
 * {@link SpringSessionLiteSseRegistry}/{@link InMemorySessionEventBroadcaster}, and a real
 * {@link SpringSessionLiteSseSessionEventListener} wired to the service's event publisher — so a
 * session destroyed by the sweep goes through the exact same
 * {@code SpringSessionLiteSessionDestroyedEvent} path that an explicit
 * {@code POST /session/logout} would. No Spring context is started; the "event bus" here is a
 * one-line manual forward to the listener, which is all {@code @EventListener} dispatch actually
 * does for a synchronous, non-{@code @Async} listener.
 */
class SpringSessionLiteIdleWatchTaskTest {

    private SpringSessionLiteProperties properties;
    private SpringSessionLiteSessionStore store;
    private SpringSessionLiteSseRegistry registry;
    private SpringSessionLiteIdleWatchTask task;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        properties = new SpringSessionLiteProperties();
        properties.setWarningBefore(Duration.ofSeconds(60));

        store = mock(SpringSessionLiteSessionStore.class);

        SpringSessionLiteIpHasher ipHasher = new SpringSessionLiteIpHasher(properties);
        SpringSessionLiteCookieManager cookieManager = new SpringSessionLiteCookieManager(properties);
        SpringSessionLiteIpResolver ipResolver = new SpringSessionLiteIpResolver(properties);

        registry = new SpringSessionLiteSseRegistry();
        InMemorySessionEventBroadcaster broadcaster = new InMemorySessionEventBroadcaster(registry);

        SpringSessionLiteSseSessionEventListener[] listenerRef = new SpringSessionLiteSseSessionEventListener[1];
        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof SpringSessionLiteSessionDestroyedEvent destroyed) {
                listenerRef[0].onSessionDestroyed(destroyed);
            } else if (event instanceof SpringSessionLiteSessionRenewedEvent renewed) {
                listenerRef[0].onSessionRenewed(renewed);
            }
        };

        SpringSessionLiteService sessionService = new SpringSessionLiteService(ipHasher, store, properties, ipResolver, eventPublisher, cookieManager);
        listenerRef[0] = new SpringSessionLiteSseSessionEventListener(broadcaster, sessionService);

        task = new SpringSessionLiteIdleWatchTask(store, sessionService, properties, broadcaster);

        emitter = mock(SseEmitter.class);
    }

    private SpringSessionLiteSession session(String userId, String sessionId, Instant expiresAt, Instant lastAccessedAt) {
        SpringSessionLiteSession session = new SpringSessionLiteSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setEmail(userId + "@test.com");
        session.setIpHash("irrelevant-for-this-task");
        session.setCreatedAt(lastAccessedAt);
        session.setExpiresAt(expiresAt);
        session.setLastAccessedAt(lastAccessedAt);
        return session;
    }

    private void stubActiveSession(SpringSessionLiteSession session) {
        when(store.findActive(any())).thenReturn(List.of(session));
        when(store.findBySessionId(session.getSessionId())).thenReturn(Optional.of(session));
    }

    private Set<ResponseBodyEmitter.DataWithMediaType> capturedEventNamed(String name) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.atLeastOnce()).send(captor.capture());

        return captor.getAllValues().stream()
                .map(SseEmitter.SseEventBuilder::build)
                .filter(built -> built.stream()
                        .map(ResponseBodyEmitter.DataWithMediaType::getData)
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .anyMatch(text -> text.contains("event:" + name)))
                .findFirst()
                .orElse(null);
    }

    private <T> T nonStringPayloadOf(Set<ResponseBodyEmitter.DataWithMediaType> built, Class<T> type) {
        return built.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " payload in " + built));
    }

    // --- logout: idle crossed ---

    @Test
    void evaluateDestroysSessionAndPushesLogoutWhenIdleExpired() throws IOException {
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = session("user-1", "sid-1", now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(11)));
        stubActiveSession(session);
        registry.add("user-1", emitter);

        task.evaluate();

        verify(store).deleteBySessionId("sid-1");
        Set<ResponseBodyEmitter.DataWithMediaType> logoutEvent = capturedEventNamed("logout");
        assertThat(logoutEvent).as("a logout event must have been pushed").isNotNull();
        assertThat(nonStringPayloadOf(logoutEvent, SpringSessionLiteSseLogoutEvent.class).sessionId()).isEqualTo("sid-1");
    }

    // --- logout: absolute crossed ---

    @Test
    void evaluateDestroysSessionAndPushesLogoutWhenAbsoluteExpired() throws IOException {
        // findActive(now) in the real JPA store only ever returns expiresAt > now rows, so this
        // exact state (expiresAt already in the past) would not normally reach evaluateSession()
        // in production — the task's own javadoc documents that narrow gap. This test exercises
        // the evaluation branch directly regardless, proving the "absolute" check itself is
        // correct if such a row were ever handed to it (e.g. a race between the query and
        // evaluation instants).
        Instant now = Instant.now();
        SpringSessionLiteSession session = session("user-2", "sid-2", now.minus(Duration.ofSeconds(1)), now.minus(Duration.ofMinutes(1)));
        stubActiveSession(session);
        registry.add("user-2", emitter);

        task.evaluate();

        verify(store).deleteBySessionId("sid-2");
        Set<ResponseBodyEmitter.DataWithMediaType> logoutEvent = capturedEventNamed("logout");
        assertThat(logoutEvent).as("a logout event must have been pushed").isNotNull();
        assertThat(nonStringPayloadOf(logoutEvent, SpringSessionLiteSseLogoutEvent.class).sessionId()).isEqualTo("sid-2");
    }

    // --- warning: idle window ---

    @Test
    void evaluatePushesWarningWithIdleCauseWithinWarningBeforeWindow() throws IOException {
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.setWarningBefore(Duration.ofSeconds(60));
        Instant now = Instant.now();
        // idle deadline = lastAccessedAt(now-9m30s) + maxIdle(10m) = now+30s -> inside the 60s window
        SpringSessionLiteSession session = session("user-3", "sid-3", now.plus(Duration.ofHours(2)), now.minus(Duration.ofSeconds(9 * 60 + 30)));
        stubActiveSession(session);
        registry.add("user-3", emitter);

        task.evaluate();

        verify(store, never()).deleteBySessionId(any());
        Set<ResponseBodyEmitter.DataWithMediaType> warningEvent = capturedEventNamed("warning");
        assertThat(warningEvent).as("a warning event must have been pushed").isNotNull();
        SpringSessionLiteSseWarningEvent payload = nonStringPayloadOf(warningEvent, SpringSessionLiteSseWarningEvent.class);
        assertThat(payload.sessionId()).isEqualTo("sid-3");
        assertThat(payload.cause()).isEqualTo("idle");
        assertThat(payload.remainingMs()).isCloseTo(30_000L, offset(5_000L));
        assertThat(payload.idleRemainingMs()).isNotNull();
    }

    // --- warning: absolute window ---

    @Test
    void evaluatePushesWarningWithAbsoluteCauseWithinWarningBeforeWindow() throws IOException {
        properties.setWarningBefore(Duration.ofSeconds(60));
        Instant now = Instant.now();
        SpringSessionLiteSession session = session("user-4", "sid-4", now.plus(Duration.ofSeconds(30)), now);
        stubActiveSession(session);
        registry.add("user-4", emitter);

        task.evaluate();

        verify(store, never()).deleteBySessionId(any());
        Set<ResponseBodyEmitter.DataWithMediaType> warningEvent = capturedEventNamed("warning");
        assertThat(warningEvent).as("a warning event must have been pushed").isNotNull();
        SpringSessionLiteSseWarningEvent payload = nonStringPayloadOf(warningEvent, SpringSessionLiteSseWarningEvent.class);
        assertThat(payload.sessionId()).isEqualTo("sid-4");
        assertThat(payload.cause()).isEqualTo("absolute");
        assertThat(payload.idleRemainingMs()).isNull();
        assertThat(payload.remainingMs()).isCloseTo(30_000L, offset(5_000L));
    }

    // --- no push when nothing is due ---

    @Test
    void evaluatePushesNothingWhenSessionIsFarFromAnyDeadline() throws IOException {
        Instant now = Instant.now();
        SpringSessionLiteSession session = session("user-5", "sid-5", now.plus(Duration.ofHours(1)), now);
        stubActiveSession(session);
        registry.add("user-5", emitter);

        task.evaluate();

        verify(store, never()).deleteBySessionId(any());
        assertThat(capturedEventNamed("logout")).isNull();
        assertThat(capturedEventNamed("warning")).isNull();
    }
}
