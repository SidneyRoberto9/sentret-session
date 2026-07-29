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
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseLogoutEvent;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRegistry;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRenewEvent;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseSessionEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the plan's "not just the scheduled sweep" requirement: calling the
 * {@code @EventListener} methods directly — exactly what Spring's event dispatch does for a
 * synchronous listener — pushes {@code logout}/{@code renew} immediately, independent of
 * {@code SpringSessionLiteIdleWatchTask}. This is what covers explicit
 * {@code POST /session/logout}/{@code /session/renew} pushing without waiting for the next tick.
 */
class SpringSessionLiteSseSessionEventListenerTest {

    private SpringSessionLiteSseRegistry registry;
    private SpringSessionLiteSseSessionEventListener listener;
    private SpringSessionLiteSessionStore store;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(10));

        store = mock(SpringSessionLiteSessionStore.class);

        SpringSessionLiteIpHasher ipHasher = new SpringSessionLiteIpHasher(properties);
        SpringSessionLiteCookieManager cookieManager = new SpringSessionLiteCookieManager(properties);
        SpringSessionLiteIpResolver ipResolver = new SpringSessionLiteIpResolver(properties);
        ApplicationEventPublisher unusedPublisher = event -> { };

        SpringSessionLiteService sessionService = new SpringSessionLiteService(ipHasher, store, properties, ipResolver, unusedPublisher, cookieManager);

        registry = new SpringSessionLiteSseRegistry();
        InMemorySessionEventBroadcaster broadcaster = new InMemorySessionEventBroadcaster(registry, Runnable::run);
        listener = new SpringSessionLiteSseSessionEventListener(broadcaster, sessionService);

        emitter = mock(SseEmitter.class);
    }

    private <T> T eventNamed(String name, Class<T> payloadType) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());

        Set<ResponseBodyEmitter.DataWithMediaType> built = captor.getValue().build();
        boolean hasName = built.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(text -> text.contains("event:" + name));
        assertThat(hasName).as("expected an event named " + name).isTrue();

        return built.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(payloadType::isInstance)
                .map(payloadType::cast)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void onSessionDestroyedPushesLogoutImmediatelyToTheDestroyedSession() throws IOException {
        registry.add("sid-1", emitter);

        listener.onSessionDestroyed(new SpringSessionLiteSessionDestroyedEvent("user-1", "sid-1"));

        SpringSessionLiteSseLogoutEvent payload = eventNamed("logout", SpringSessionLiteSseLogoutEvent.class);
        assertThat(payload.sessionId()).isEqualTo("sid-1");
    }

    @Test
    void onSessionDestroyedDoesNotPushToOtherSessions() {
        SseEmitter otherUsersEmitter = mock(SseEmitter.class);
        registry.add("sid-other-user", otherUsersEmitter);

        listener.onSessionDestroyed(new SpringSessionLiteSessionDestroyedEvent("user-1", "sid-1"));

        org.mockito.Mockito.verifyNoInteractions(otherUsersEmitter);
    }

    /**
     * The reported production incident, reduced. One user, two sessions: an orphan left behind by a
     * double-login, and the live one the user is actually working in. The orphan gets no heartbeat,
     * goes idle, and the sweep destroys it — which must not touch the live session's tab.
     *
     * <p>Routing on {@code userId} sent this {@code logout} to every tab of the user, so the live
     * tab terminated, POSTed its app logout URL and redirected: a session with 3s of idle killed by
     * an unrelated row. An event describes exactly one session and must reach only that session.
     */
    @Test
    void onSessionDestroyedDoesNotTerminateAnotherSessionOfTheSameUser() throws IOException {
        SseEmitter liveTabEmitter = mock(SseEmitter.class);
        registry.add("sid-orphan", emitter);
        registry.add("sid-live", liveTabEmitter);

        listener.onSessionDestroyed(new SpringSessionLiteSessionDestroyedEvent("user-1", "sid-orphan"));

        assertThat(eventNamed("logout", SpringSessionLiteSseLogoutEvent.class).sessionId()).isEqualTo("sid-orphan");
        org.mockito.Mockito.verifyNoInteractions(liveTabEmitter);
    }

    @Test
    void onSessionRenewedDoesNotPushToAnotherSessionOfTheSameUser() throws IOException {
        Instant now = Instant.now();
        SpringSessionLiteSession session = new SpringSessionLiteSession();
        session.setSessionId("sid-renewed");
        session.setUserId("user-1");
        session.setIpHash("irrelevant");
        session.setCreatedAt(now.minus(Duration.ofHours(1)));
        session.setLastAccessedAt(now);
        session.setExpiresAt(now.plus(Duration.ofMinutes(30)));
        when(store.findBySessionId("sid-renewed")).thenReturn(Optional.of(session));

        SseEmitter otherTabEmitter = mock(SseEmitter.class);
        registry.add("sid-renewed", emitter);
        registry.add("sid-other", otherTabEmitter);

        listener.onSessionRenewed(new SpringSessionLiteSessionRenewedEvent("user-1", "sid-renewed", now));

        assertThat(eventNamed("renew", SpringSessionLiteSseRenewEvent.class).sessionId()).isEqualTo("sid-renewed");
        org.mockito.Mockito.verifyNoInteractions(otherTabEmitter);
    }

    /**
     * {@link SpringSessionLiteSessionDestroyedEvent} keeps a 1-arg constructor that leaves
     * {@code userId} null for pre-2.1 compatibility. Routing on it meant
     * {@code ConcurrentHashMap.get(null)} — an NPE thrown inside a synchronous {@code @EventListener},
     * straight back into the publisher's transaction. The sessionId is never null.
     */
    @Test
    void onSessionDestroyedWithLegacyNullUserIdStillRoutesBySessionId() throws IOException {
        registry.add("sid-legacy", emitter);

        listener.onSessionDestroyed(new SpringSessionLiteSessionDestroyedEvent("sid-legacy"));

        assertThat(eventNamed("logout", SpringSessionLiteSseLogoutEvent.class).sessionId()).isEqualTo("sid-legacy");
    }

    @Test
    void onSessionRenewedPushesRenewImmediatelyWithFreshRemainingTime() throws IOException {
        Instant now = Instant.now();
        SpringSessionLiteSession session = new SpringSessionLiteSession();
        session.setSessionId("sid-2");
        session.setUserId("user-3");
        session.setIpHash("irrelevant");
        session.setCreatedAt(now.minus(Duration.ofHours(1)));
        session.setLastAccessedAt(now);
        session.setExpiresAt(now.plus(Duration.ofMinutes(30)));
        when(store.findBySessionId("sid-2")).thenReturn(Optional.of(session));

        registry.add("sid-2", emitter);

        listener.onSessionRenewed(new SpringSessionLiteSessionRenewedEvent("user-3", "sid-2", now));

        SpringSessionLiteSseRenewEvent payload = eventNamed("renew", SpringSessionLiteSseRenewEvent.class);
        assertThat(payload.sessionId()).isEqualTo("sid-2");
        assertThat(payload.absoluteRemainingMs()).isCloseTo(Duration.ofMinutes(30).toMillis(), offset(5_000L));
        assertThat(payload.idleRemainingMs()).isCloseTo(Duration.ofMinutes(10).toMillis(), offset(5_000L));
    }

    /**
     * Since 2.3.0 the publisher puts the remaining-time snapshot on the event — it had just loaded
     * and saved the row — so the listener must use it instead of re-reading the row one frame later.
     */
    @Test
    void onSessionRenewedUsesTheSnapshotOnTheEventWithoutReReadingTheSession() throws IOException {
        registry.add("sid-carried", emitter);

        listener.onSessionRenewed(new SpringSessionLiteSessionRenewedEvent("user-5", "sid-carried", Instant.now(), 30_000L, 5_000L));

        SpringSessionLiteSseRenewEvent payload = eventNamed("renew", SpringSessionLiteSseRenewEvent.class);
        assertThat(payload.absoluteRemainingMs()).isEqualTo(30_000L);
        assertThat(payload.idleRemainingMs()).isEqualTo(5_000L);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).findBySessionId(any());
    }

    @Test
    void onSessionRenewedFallsBackToZeroWhenSessionAlreadyGone() throws IOException {
        when(store.findBySessionId("sid-missing")).thenReturn(Optional.empty());
        registry.add("sid-missing", emitter);

        listener.onSessionRenewed(new SpringSessionLiteSessionRenewedEvent("user-4", "sid-missing", Instant.now()));

        SpringSessionLiteSseRenewEvent payload = eventNamed("renew", SpringSessionLiteSseRenewEvent.class);
        assertThat(payload.sessionId()).isEqualTo("sid-missing");
        assertThat(payload.absoluteRemainingMs()).isZero();
        assertThat(payload.idleRemainingMs()).isNull();
    }
}
