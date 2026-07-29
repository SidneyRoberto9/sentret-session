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
        InMemorySessionEventBroadcaster broadcaster = new InMemorySessionEventBroadcaster(registry, Runnable::run);

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

        task = new SpringSessionLiteIdleWatchTask(broadcaster, store, properties, sessionService);

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
        stubActiveSessions(session);
    }

    private void stubActiveSessions(SpringSessionLiteSession... sessions) {
        when(store.findActive(any())).thenReturn(List.of(sessions));

        for (SpringSessionLiteSession session : sessions) {
            when(store.findBySessionId(session.getSessionId())).thenReturn(Optional.of(session));
        }
    }

    private Set<ResponseBodyEmitter.DataWithMediaType> capturedEventNamed(String name) throws IOException {
        return capturedEventNamed(emitter, name);
    }

    /**
     * Asserts on the <em>named</em> events a specific emitter received, never on the absence of any
     * interaction: {@code evaluate()} ends with {@code broadcaster.pingAll()}, so every connected
     * emitter — bystanders included — legitimately gets a keep-alive comment. {@code verifyNoInteractions}
     * would therefore fail on a correctly-isolated bystander. Returns {@code null} when no event of
     * that name was sent to {@code target}.
     */
    private Set<ResponseBodyEmitter.DataWithMediaType> capturedEventNamed(SseEmitter target, String name) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(target, org.mockito.Mockito.atLeastOnce()).send(captor.capture());

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
        registry.add("sid-1", emitter);

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
        registry.add("sid-2", emitter);

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
        registry.add("sid-3", emitter);

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
        registry.add("sid-4", emitter);

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
        registry.add("sid-5", emitter);

        task.evaluate();

        verify(store, never()).deleteBySessionId(any());
        assertThat(capturedEventNamed("logout")).isNull();
        assertThat(capturedEventNamed("warning")).isNull();
    }

    /**
     * The N+1 guard. Until 2.3.0 the sweep called {@code sessionService.remaining(sessionId)} per
     * row, which re-read via {@code findBySessionId} — one extra read-only transaction per live
     * session on every tick. The remaining-time maths only needs {@code expiresAt} and
     * {@code lastAccessedAt}, both already on the row {@code findActive()} returned, so a session
     * that is nowhere near either deadline — which is nearly all of them, nearly all the time — must
     * cost nothing beyond the single {@code findActive()} query. (Sessions the triage flags for
     * action do get re-read, deliberately; see
     * {@link #evaluateDoesNotLogOutASessionWhoseHeartbeatLandedDuringTheSweep()}.)
     */
    @Test
    void evaluateQueriesTheStoreOncePerTickWhenNoSessionNeedsAction() {
        Instant now = Instant.now();
        stubActiveSessions(
                session("user-a", "sid-a", now.plus(Duration.ofHours(1)), now),
                session("user-b", "sid-b", now.plus(Duration.ofHours(2)), now),
                session("user-c", "sid-c", now.plus(Duration.ofHours(3)), now)
        );

        task.evaluate();

        verify(store).findActive(any());
        verify(store, never()).findBySessionId(any());
    }

    // --- warning: both idle and absolute deadlines are within the warning window ---

    @Test
    void evaluatePushesIdleCauseWhenIdleDeadlineIsNearerThanAbsolute() throws IOException {
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.setWarningBefore(Duration.ofMinutes(5));
        Instant now = Instant.now();
        // idle deadline = lastAccessedAt(now-8m) + maxIdle(10m) = now+2m
        // absolute deadline = now+4m
        SpringSessionLiteSession session = session("user-7", "sid-7", now.plus(Duration.ofMinutes(4)), now.minus(Duration.ofMinutes(8)));
        stubActiveSession(session);
        registry.add("sid-7", emitter);

        task.evaluate();

        Set<ResponseBodyEmitter.DataWithMediaType> warningEvent = capturedEventNamed("warning");
        assertThat(warningEvent).as("a warning event must have been pushed").isNotNull();
        assertThat(nonStringPayloadOf(warningEvent, SpringSessionLiteSseWarningEvent.class).cause()).isEqualTo("idle");
    }

    @Test
    void evaluatePushesAbsoluteCauseWhenAbsoluteDeadlineIsNearerThanIdle() throws IOException {
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.setWarningBefore(Duration.ofMinutes(5));
        Instant now = Instant.now();
        // idle deadline = lastAccessedAt(now-6m) + maxIdle(10m) = now+4m (within the 5m window)
        // absolute deadline = now+2m (nearer than idle's +4m, also within the 5m window)
        SpringSessionLiteSession session = session("user-8", "sid-8", now.plus(Duration.ofMinutes(2)), now.minus(Duration.ofMinutes(6)));
        stubActiveSession(session);
        registry.add("sid-8", emitter);

        task.evaluate();

        Set<ResponseBodyEmitter.DataWithMediaType> warningEvent = capturedEventNamed("warning");
        assertThat(warningEvent).as("a warning event must have been pushed").isNotNull();
        assertThat(nonStringPayloadOf(warningEvent, SpringSessionLiteSseWarningEvent.class).cause()).isEqualTo("absolute");
    }

    // --- race: session vanishes between findActive() and the per-session evaluation ---

    /**
     * {@code findActive()} snapshots every row at the top of the sweep; by the time a given row is
     * evaluated the session may have been destroyed (an explicit {@code POST /session/logout}, or
     * another node's cleanup). Warning a session that no longer exists puts a countdown modal on a
     * tab that has already been told to log out.
     */
    @Test
    void evaluateSkipsSessionGoneBeforeItCanBeEvaluated() throws IOException {
        properties.setWarningBefore(Duration.ofSeconds(60));
        Instant now = Instant.now();
        // Inside the warning window, so the sweep does try to act on it — which is the only case
        // where the session's continued existence matters.
        SpringSessionLiteSession session = session("user-6", "sid-6", now.plus(Duration.ofSeconds(30)), now);
        when(store.findActive(any())).thenReturn(List.of(session));
        when(store.findBySessionId("sid-6")).thenReturn(Optional.empty());
        registry.add("sid-6", emitter);

        task.evaluate();

        verify(store, never()).deleteBySessionId(any());
        assertThat(capturedEventNamed("logout")).isNull();
        assertThat(capturedEventNamed("warning")).isNull();
    }

    // --- race: the user is active while the sweep is running ---

    /**
     * The snapshot the sweep iterates is as old as the sweep is long. A user who moves their mouse
     * after {@code findActive()} loaded their row — {@code POST /session/heartbeat} writes
     * {@code lastAccessedAt} — must not be logged out by a decision taken on the stale copy. Acting
     * on the re-read is what makes the window microseconds wide again instead of sweep-wide.
     */
    @Test
    void evaluateDoesNotLogOutASessionWhoseHeartbeatLandedDuringTheSweep() throws IOException {
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();

        SpringSessionLiteSession stale = session("user-9", "sid-9", now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(11)));
        SpringSessionLiteSession refreshed = session("user-9", "sid-9", now.plus(Duration.ofHours(1)), now);

        when(store.findActive(any())).thenReturn(List.of(stale));
        when(store.findBySessionId("sid-9")).thenReturn(Optional.of(refreshed));
        registry.add("sid-9", emitter);

        task.evaluate();

        verify(store, never()).deleteBySessionId(any());
        assertThat(capturedEventNamed("logout")).as("an active user must not be logged out").isNull();
        assertThat(capturedEventNamed("warning")).as("nor warned — they are nowhere near idle").isNull();
    }

    // --- one user, two sessions: the reported incident, through the real production path ---

    /**
     * A session orphaned by a double-login receives no heartbeat and drifts into the warning window
     * while the user works happily in another session. Routing by userId pushed that warning to the
     * live tab, which then showed an inactivity countdown it could not dismiss — clicking "Continuar
     * conectado" renewed the *cookie's* session, so the orphan kept re-warning every 10s and the
     * modal came straight back.
     */
    @Test
    void evaluateWarnsOnlyTheIdleSessionsOwnTabNotAnotherSessionOfTheSameUser() throws IOException {
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.setWarningBefore(Duration.ofSeconds(60));
        Instant now = Instant.now();

        SseEmitter liveTabEmitter = mock(SseEmitter.class);
        SpringSessionLiteSession orphan = session("user-1", "sid-orphan", now.plus(Duration.ofHours(2)), now.minus(Duration.ofSeconds(9 * 60 + 30)));
        SpringSessionLiteSession live = session("user-1", "sid-live", now.plus(Duration.ofHours(2)), now);
        stubActiveSessions(orphan, live);
        registry.add("sid-orphan", emitter);
        registry.add("sid-live", liveTabEmitter);

        task.evaluate();

        Set<ResponseBodyEmitter.DataWithMediaType> warningEvent = capturedEventNamed(emitter, "warning");
        assertThat(warningEvent).as("the idle session's own tab must be warned").isNotNull();
        assertThat(nonStringPayloadOf(warningEvent, SpringSessionLiteSseWarningEvent.class).sessionId()).isEqualTo("sid-orphan");

        assertThat(capturedEventNamed(liveTabEmitter, "warning"))
                .as("the live session's tab must not be warned about another session")
                .isNull();
    }

    /**
     * The end of that same story, and the worst of it: when the orphan finally crosses maxIdle the
     * sweep destroys it, and routing the resulting {@code logout} by userId terminated the live tab
     * too — POSTing its app logout URL and redirecting a user who was actively typing. Driven here
     * through the real path: sweep -> service.logout -> SessionDestroyedEvent -> listener ->
     * broadcaster.
     */
    @Test
    void evaluateDestroysIdleSessionWithoutLoggingOutAnotherSessionOfTheSameUser() throws IOException {
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();

        SseEmitter liveTabEmitter = mock(SseEmitter.class);
        SpringSessionLiteSession orphan = session("user-1", "sid-orphan", now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(11)));
        SpringSessionLiteSession live = session("user-1", "sid-live", now.plus(Duration.ofHours(1)), now);
        stubActiveSessions(orphan, live);
        registry.add("sid-orphan", emitter);
        registry.add("sid-live", liveTabEmitter);

        task.evaluate();

        verify(store).deleteBySessionId("sid-orphan");
        verify(store, never()).deleteBySessionId("sid-live");

        Set<ResponseBodyEmitter.DataWithMediaType> logoutEvent = capturedEventNamed(emitter, "logout");
        assertThat(logoutEvent).as("the destroyed session's own tab must be told").isNotNull();
        assertThat(nonStringPayloadOf(logoutEvent, SpringSessionLiteSseLogoutEvent.class).sessionId()).isEqualTo("sid-orphan");

        assertThat(capturedEventNamed(liveTabEmitter, "logout"))
                .as("a live session must never be logged out by another session expiring")
                .isNull();
    }
}
