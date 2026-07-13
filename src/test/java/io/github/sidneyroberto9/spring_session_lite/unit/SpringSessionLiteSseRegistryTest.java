package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringSessionLiteSseRegistryTest {

    private final SpringSessionLiteSseRegistry registry = new SpringSessionLiteSseRegistry();

    @Test
    void emittersForReturnsEmptyListWhenUserHasNoEmitter() {
        assertThat(registry.emittersFor("user-1")).isEmpty();
    }

    @Test
    void addRegistersEmitterUnderUserId() {
        SseEmitter emitter = mock(SseEmitter.class);

        registry.add("user-1", emitter);

        assertThat(registry.emittersFor("user-1")).containsExactly(emitter);
        assertThat(registry.connectedUserIds()).containsExactly("user-1");
    }

    @Test
    void addAllowsMultipleEmittersForTheSameUser() {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);

        registry.add("user-1", first);
        registry.add("user-1", second);

        assertThat(registry.emittersFor("user-1")).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void removeDropsOnlyTheGivenEmitter() {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        registry.add("user-1", first);
        registry.add("user-1", second);

        registry.remove("user-1", first);

        assertThat(registry.emittersFor("user-1")).containsExactly(second);
    }

    @Test
    void removeLastEmitterDropsTheUserFromConnectedUserIds() {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.add("user-1", emitter);

        registry.remove("user-1", emitter);

        assertThat(registry.emittersFor("user-1")).isEmpty();
        assertThat(registry.connectedUserIds()).doesNotContain("user-1");
    }

    @Test
    void removeIsNoOpWhenUserOrEmitterUnknown() {
        registry.remove("missing-user", mock(SseEmitter.class));

        assertThat(registry.connectedUserIds()).isEmpty();
    }

    @Test
    void connectedUserIdsReflectsAllUsersWithLiveEmitters() {
        registry.add("user-1", mock(SseEmitter.class));
        registry.add("user-2", mock(SseEmitter.class));

        assertThat(registry.connectedUserIds()).containsExactlyInAnyOrder("user-1", "user-2");
    }
}
