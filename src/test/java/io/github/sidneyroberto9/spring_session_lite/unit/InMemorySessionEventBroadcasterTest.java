package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.web.sse.InMemorySessionEventBroadcaster;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseLogoutEvent;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRegistry;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRenewEvent;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseWarningEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

    private final SpringSessionLiteSseRegistry registry = new SpringSessionLiteSseRegistry();
    private final InMemorySessionEventBroadcaster broadcaster = new InMemorySessionEventBroadcaster(registry);

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
}
