package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringSessionLiteSseRegistryTest {

    private final SpringSessionLiteSseRegistry registry = new SpringSessionLiteSseRegistry();

    @Test
    void emittersForSessionReturnsEmptyListWhenSessionHasNoEmitter() {
        assertThat(registry.emittersForSession("sid-1")).isEmpty();
    }

    @Test
    void addRegistersEmitterUnderSessionId() {
        SseEmitter emitter = mock(SseEmitter.class);

        registry.add("sid-1", emitter);

        assertThat(registry.emittersForSession("sid-1")).containsExactly(emitter);
        assertThat(registry.connectedSessionIds()).containsExactly("sid-1");
    }

    /** Two tabs sharing one session cookie: one key, two emitters. A logout does apply to both. */
    @Test
    void addAllowsMultipleEmittersForTheSameSession() {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);

        registry.add("sid-1", first);
        registry.add("sid-1", second);

        assertThat(registry.emittersForSession("sid-1")).containsExactlyInAnyOrder(first, second);
    }

    /**
     * The keying that the whole fix rests on: one user on two devices is two independent keys.
     * Sharing a key is what let one session's logout terminate the other.
     */
    @Test
    void twoSessionsOfTheSameUserAreIndependentKeys() {
        SseEmitter deviceA = mock(SseEmitter.class);
        SseEmitter deviceB = mock(SseEmitter.class);

        registry.add("sid-device-a", deviceA);
        registry.add("sid-device-b", deviceB);

        assertThat(registry.emittersForSession("sid-device-a")).containsExactly(deviceA);
        assertThat(registry.emittersForSession("sid-device-b")).containsExactly(deviceB);
        assertThat(registry.connectedSessionIds()).containsExactlyInAnyOrder("sid-device-a", "sid-device-b");
    }

    @Test
    void removeDropsOnlyTheGivenEmitter() {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        registry.add("sid-1", first);
        registry.add("sid-1", second);

        registry.remove("sid-1", first);

        assertThat(registry.emittersForSession("sid-1")).containsExactly(second);
    }

    @Test
    void removeLastEmitterDropsTheSessionFromConnectedSessionIds() {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.add("sid-1", emitter);

        registry.remove("sid-1", emitter);

        assertThat(registry.emittersForSession("sid-1")).isEmpty();
        assertThat(registry.connectedSessionIds()).doesNotContain("sid-1");
    }

    @Test
    void removeIsNoOpWhenSessionOrEmitterUnknown() {
        registry.remove("missing-session", mock(SseEmitter.class));

        assertThat(registry.connectedSessionIds()).isEmpty();
    }

    @Test
    void connectedSessionIdsReflectsAllSessionsWithLiveEmitters() {
        registry.add("sid-1", mock(SseEmitter.class));
        registry.add("sid-2", mock(SseEmitter.class));

        assertThat(registry.connectedSessionIds()).containsExactlyInAnyOrder("sid-1", "sid-2");
    }
}
