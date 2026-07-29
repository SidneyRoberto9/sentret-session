package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.web.sse.InMemorySessionEventBroadcaster;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SessionEventBroadcaster;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseLogoutEvent;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRegistry;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRenewEvent;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseWarningEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Exercises {@link InMemorySessionEventBroadcaster} against a real
 * {@link SpringSessionLiteSseRegistry}, with a Mockito-mocked {@link SseEmitter} standing in for
 * the real transport object (see class javadoc on why: a bare, un-{@code initialize}d real
 * {@code SseEmitter} never throws on {@code send} and offers no public way to inspect what was
 * sent — {@code initialize(Handler)} is package-private to Spring's own package). The captured
 * {@link SseEmitter.SseEventBuilder} is {@code build()}-ed (a public method) to inspect the exact
 * event name and payload object that would have gone out over the wire, so these assertions cover
 * real behavior of the broadcaster, not just "send was called".
 */
class InMemorySessionEventBroadcasterTest {

    private static final long AWAIT_SECONDS = 5;

    private final SpringSessionLiteSseRegistry registry = new SpringSessionLiteSseRegistry();

    /**
     * Fed {@code Runnable::run} so the routing/payload assertions below stay synchronous and
     * deterministic. The real dispatch behavior — its own threads, ordering, the drop-on-rejection
     * path, {@link InMemorySessionEventBroadcaster#close()} — is covered separately against
     * {@link #asyncBroadcaster}, which uses the production executors.
     */
    private final InMemorySessionEventBroadcaster broadcaster = new InMemorySessionEventBroadcaster(registry, Runnable::run);

    private final InMemorySessionEventBroadcaster asyncBroadcaster = new InMemorySessionEventBroadcaster(registry);

    @AfterEach
    void closeAsyncBroadcaster() {
        asyncBroadcaster.close();
    }

    private static String eventNameOf(Set<ResponseBodyEmitter.DataWithMediaType> built) {
        return built.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(text -> text.contains("event:"))
                .findFirst()
                .map(text -> text.substring(text.indexOf("event:") + "event:".length(), text.indexOf('\n', text.indexOf("event:"))))
                .orElseThrow(() -> new AssertionError("no event: line found in " + built));
    }

    private static Object payloadOf(Set<ResponseBodyEmitter.DataWithMediaType> built) {
        return built.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(data -> !(data instanceof String))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no non-string data payload found in " + built));
    }

    @Test
    void logoutPushesEventNamedLogoutWithSessionIdPayload() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.add("sid-1", emitter);

        broadcaster.sendLogout("sid-1", new SpringSessionLiteSseLogoutEvent("sid-1"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());

        Set<ResponseBodyEmitter.DataWithMediaType> built = captor.getValue().build();
        assertThat(eventNameOf(built)).isEqualTo("logout");
        assertThat(payloadOf(built)).isEqualTo(new SpringSessionLiteSseLogoutEvent("sid-1"));
    }

    @Test
    void renewPushesEventNamedRenewWithRemainingMsPayload() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.add("sid-2", emitter);

        broadcaster.sendRenew("sid-2", new SpringSessionLiteSseRenewEvent("sid-2", 30_000L, 5_000L));

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());

        Set<ResponseBodyEmitter.DataWithMediaType> built = captor.getValue().build();
        assertThat(eventNameOf(built)).isEqualTo("renew");
        assertThat(payloadOf(built)).isEqualTo(new SpringSessionLiteSseRenewEvent("sid-2", 30_000L, 5_000L));
    }

    @Test
    void warningPushesEventNamedWarningWithCausePayload() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.add("sid-3", emitter);

        broadcaster.sendWarning("sid-3", new SpringSessionLiteSseWarningEvent("sid-3", 4_000L, 4_000L, null, "absolute"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());

        Set<ResponseBodyEmitter.DataWithMediaType> built = captor.getValue().build();
        assertThat(eventNameOf(built)).isEqualTo("warning");
        assertThat(payloadOf(built)).isEqualTo(new SpringSessionLiteSseWarningEvent("sid-3", 4_000L, 4_000L, null, "absolute"));
    }

    @Test
    void broadcastFansOutToEveryEmitterRegisteredForTheSession() throws IOException {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        registry.add("sid-4", first);
        registry.add("sid-4", second);

        broadcaster.sendLogout("sid-4", new SpringSessionLiteSseLogoutEvent("sid-4"));

        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(second).send(any(SseEmitter.SseEventBuilder.class));
    }

    /**
     * Isolation at the broadcaster: a send addressed to one session must not touch a peer session's
     * emitter, even though both may belong to the same user. Keying by userId is what made this
     * impossible and turned one session's expiry into everyone's logout.
     */
    @Test
    void sendLogoutReachesOnlyTheTargetSession() throws IOException {
        SseEmitter targetTab = mock(SseEmitter.class);
        SseEmitter peerTab = mock(SseEmitter.class);
        registry.add("sid-target", targetTab);
        registry.add("sid-peer", peerTab);

        broadcaster.sendLogout("sid-target", new SpringSessionLiteSseLogoutEvent("sid-target"));

        verify(targetTab).send(any(SseEmitter.SseEventBuilder.class));
        verifyNoInteractions(peerTab);
    }

    @Test
    void sendWarningReachesOnlyTheTargetSession() throws IOException {
        SseEmitter targetTab = mock(SseEmitter.class);
        SseEmitter peerTab = mock(SseEmitter.class);
        registry.add("sid-target", targetTab);
        registry.add("sid-peer", peerTab);

        broadcaster.sendWarning("sid-target", new SpringSessionLiteSseWarningEvent("sid-target", 4_000L, 4_000L, null, "idle"));

        verify(targetTab).send(any(SseEmitter.SseEventBuilder.class));
        verifyNoInteractions(peerTab);
    }

    @Test
    void broadcastToUnknownSessionDoesNotThrow() {
        // No registry.add(...) for this sessionId — must be a silent no-op, not an error, since most
        // sessions won't have a live SSE connection at all.
        broadcaster.sendLogout("ghost-session", new SpringSessionLiteSseLogoutEvent("sid-5"));
    }

    @Test
    void deadEmitterIsRemovedFromRegistryOnIOException() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        registry.add("sid-6", emitter);

        broadcaster.sendLogout("sid-6", new SpringSessionLiteSseLogoutEvent("sid-6"));

        assertThat(registry.emittersForSession("sid-6")).isEmpty();
    }

    @Test
    void pingAllSendsCommentToEveryConnectedEmitterAcrossAllSessions() throws IOException {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        registry.add("sid-7", first);
        registry.add("sid-8", second);

        broadcaster.pingAll();

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(first).send(captor.capture());
        Set<ResponseBodyEmitter.DataWithMediaType> built = captor.getValue().build();
        assertThat(built).hasSize(1);
        assertThat(built.iterator().next().getData()).asString().contains(":ping");

        verify(second).send(any(SseEmitter.SseEventBuilder.class));
    }

    // --- dispatch: nothing is sent on the caller's thread ---

    /**
     * The whole point of 2.3.0's dispatch change. The callers are the idle-watch sweep (on the host
     * application's shared {@code TaskScheduler}) and the domain-event listener (inside
     * {@code SpringSessionLiteService}'s open transaction) — neither may execute a blocking socket
     * write itself.
     */
    @Test
    void sendRunsOnTheBroadcastersOwnThreadNotTheCallers() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        CountDownLatch sent = new CountDownLatch(1);
        AtomicReference<String> sendingThread = new AtomicReference<>();

        doAnswer(invocation -> {
            sendingThread.set(Thread.currentThread().getName());
            sent.countDown();
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        registry.add("sid-async", emitter);

        asyncBroadcaster.sendLogout("sid-async", new SpringSessionLiteSseLogoutEvent("sid-async"));

        assertThat(sent.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the event must actually go out").isTrue();
        assertThat(sendingThread.get()).startsWith("spring-session-lite-sse-send-");
        assertThat(sendingThread.get()).isNotEqualTo(Thread.currentThread().getName());
    }

    /**
     * A client whose socket never drains blocks {@code SseEmitter#send} indefinitely (emitters are
     * created with no timeout). The caller must be long gone by then — before 2.3.0 it was the
     * scheduler thread, and it stayed there.
     */
    @Test
    void aWedgedClientDoesNotBlockTheCaller() throws Exception {
        SseEmitter wedged = mock(SseEmitter.class);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        doAnswer(invocation -> {
            sendStarted.countDown();
            release.await();
            return null;
        }).when(wedged).send(any(SseEmitter.SseEventBuilder.class));

        registry.add("sid-wedged", wedged);

        try {
            asyncBroadcaster.sendLogout("sid-wedged", new SpringSessionLiteSseLogoutEvent("sid-wedged"));

            assertThat(sendStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("the send must have begun").isTrue();
            // Reaching this line with the send still parked inside the emitter is the assertion:
            // the caller returned while the write is stuck.
            assertThat(release.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    /**
     * Ordering is why sends are striped by sessionId onto single-thread queues instead of being
     * thrown at a pool: a tab that receives {@code warning} <em>after</em> the {@code logout} that
     * ended the session shows a countdown modal for a session that no longer exists.
     */
    @Test
    void eventsForOneSessionArriveInTheOrderTheyWereProduced() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch both = new CountDownLatch(2);

        doAnswer(invocation -> {
            received.add(eventNameOf(invocation.getArgument(0, SseEmitter.SseEventBuilder.class).build()));
            both.countDown();
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        registry.add("sid-ordered", emitter);

        asyncBroadcaster.sendWarning("sid-ordered", new SpringSessionLiteSseWarningEvent("sid-ordered", 1_000L, 1_000L, null, "absolute"));
        asyncBroadcaster.sendLogout("sid-ordered", new SpringSessionLiteSseLogoutEvent("sid-ordered"));

        assertThat(both.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly("warning", "logout");
    }

    // --- lifecycle ---

    @Test
    void closeShutsDownTheExecutorsItCreated() throws IOException {
        InMemorySessionEventBroadcaster owning = new InMemorySessionEventBroadcaster(registry);
        SseEmitter emitter = mock(SseEmitter.class);
        registry.add("sid-closed", emitter);

        owning.close();
        owning.sendLogout("sid-closed", new SpringSessionLiteSseLogoutEvent("sid-closed"));

        // A shut-down executor rejects, and a rejected event is dropped rather than thrown at the
        // caller: nothing can reach the emitter afterwards.
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    /**
     * The two-arg constructor is documented as "you own the thread". Spring's inferred destroy
     * method fires {@code close()} on context shutdown, and killing a pool the consumer also uses
     * for unrelated work would cancel that work.
     */
    @Test
    void closeDoesNotShutDownACallerSuppliedExecutor() {
        ExecutorService callersPool = Executors.newSingleThreadExecutor();

        try {
            new InMemorySessionEventBroadcaster(registry, callersPool).close();

            assertThat(callersPool.isShutdown()).isFalse();
        } finally {
            callersPool.shutdownNow();
        }
    }

    @Test
    void aRejectedSendIsDroppedNotThrownAtTheCaller() {
        Executor alwaysRejects = task -> {
            throw new RejectedExecutionException("queue full");
        };
        InMemorySessionEventBroadcaster saturated = new InMemorySessionEventBroadcaster(registry, alwaysRejects);
        registry.add("sid-saturated", mock(SseEmitter.class));

        assertThatCode(() -> saturated.sendLogout("sid-saturated", new SpringSessionLiteSseLogoutEvent("sid-saturated")))
                .doesNotThrowAnyException();
    }

    /**
     * {@code close()} is on the interface (not just the default implementation) so that a consumer
     * who wraps or replaces the broadcaster still gets a shutdown hook. A stateless implementation
     * inherits the no-op.
     */
    @Test
    void interfaceCloseDefaultsToNoOp() {
        SessionEventBroadcaster stateless = new SessionEventBroadcaster() {
            @Override
            public void sendLogout(String sessionId, SpringSessionLiteSseLogoutEvent event) {
            }

            @Override
            public void sendRenew(String sessionId, SpringSessionLiteSseRenewEvent event) {
            }

            @Override
            public void sendWarning(String sessionId, SpringSessionLiteSseWarningEvent event) {
            }

            @Override
            public void pingAll() {
            }
        };

        assertThatCode(stateless::close).doesNotThrowAnyException();
    }
}
