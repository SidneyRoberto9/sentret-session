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
        InMemorySessionEventBroadcaster broadcaster = new InMemorySessionEventBroadcaster(registry);
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
    void onSessionDestroyedPushesLogoutImmediatelyToTheDestroyedSessionsUser() throws IOException {
        registry.add("user-1", emitter);

        listener.onSessionDestroyed(new SpringSessionLiteSessionDestroyedEvent("user-1", "sid-1"));

        SpringSessionLiteSseLogoutEvent payload = eventNamed("logout", SpringSessionLiteSseLogoutEvent.class);
        assertThat(payload.sessionId()).isEqualTo("sid-1");
    }

    @Test
    void onSessionDestroyedDoesNotPushToOtherUsers() {
        SseEmitter otherUsersEmitter = mock(SseEmitter.class);
        registry.add("user-2", otherUsersEmitter);

        listener.onSessionDestroyed(new SpringSessionLiteSessionDestroyedEvent("user-1", "sid-1"));

        org.mockito.Mockito.verifyNoInteractions(otherUsersEmitter);
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

        registry.add("user-3", emitter);

        listener.onSessionRenewed(new SpringSessionLiteSessionRenewedEvent("user-3", "sid-2", now));

        SpringSessionLiteSseRenewEvent payload = eventNamed("renew", SpringSessionLiteSseRenewEvent.class);
        assertThat(payload.sessionId()).isEqualTo("sid-2");
        assertThat(payload.absoluteRemainingMs()).isCloseTo(Duration.ofMinutes(30).toMillis(), offset(5_000L));
        assertThat(payload.idleRemainingMs()).isCloseTo(Duration.ofMinutes(10).toMillis(), offset(5_000L));
    }

    @Test
    void onSessionRenewedFallsBackToZeroWhenSessionAlreadyGone() throws IOException {
        when(store.findBySessionId("sid-missing")).thenReturn(Optional.empty());
        registry.add("user-4", emitter);

        listener.onSessionRenewed(new SpringSessionLiteSessionRenewedEvent("user-4", "sid-missing", Instant.now()));

        SpringSessionLiteSseRenewEvent payload = eventNamed("renew", SpringSessionLiteSseRenewEvent.class);
        assertThat(payload.sessionId()).isEqualTo("sid-missing");
        assertThat(payload.absoluteRemainingMs()).isZero();
        assertThat(payload.idleRemainingMs()).isNull();
    }
}
