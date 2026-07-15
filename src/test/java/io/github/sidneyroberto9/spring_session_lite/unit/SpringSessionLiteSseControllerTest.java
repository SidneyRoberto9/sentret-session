package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseController;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SpringSessionLiteSseControllerTest {

    private final SpringSessionLiteSseRegistry registry = mock(SpringSessionLiteSseRegistry.class);
    private final SpringSessionLiteSseController controller = new SpringSessionLiteSseController(registry);

    /**
     * {@code onCompletion}/{@code onTimeout}/{@code onError} only register a delegate on
     * {@link ResponseBodyEmitter}'s private callback fields; without a live Servlet async dispatch
     * there is no other way to actually fire them, so the fields are read via reflection and
     * invoked directly through their public {@code Runnable}/{@code Consumer} interfaces.
     */
    private static Object callback(SseEmitter emitter, String fieldName) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(emitter);
    }

    @Test
    void streamSetsHeaderAndRegistersEmitter() {
        SpringSessionLiteUser user = new SpringSessionLiteUser("user-1", "user@test.com", "sid-1", List.of());
        HttpServletResponse response = mock(HttpServletResponse.class);

        SseEmitter emitter = controller.stream(user, response);

        verify(response).setHeader("X-Accel-Buffering", "no");
        verify(registry).add("sid-1", emitter);
    }

    @Test
    void onCompletionRemovesEmitterFromRegistry() throws Exception {
        SpringSessionLiteUser user = new SpringSessionLiteUser("user-1", "user@test.com", "sid-2", List.of());
        SseEmitter emitter = controller.stream(user, mock(HttpServletResponse.class));

        ((Runnable) callback(emitter, "completionCallback")).run();

        verify(registry).remove("sid-2", emitter);
    }

    @Test
    void onTimeoutRemovesEmitterFromRegistry() throws Exception {
        SpringSessionLiteUser user = new SpringSessionLiteUser("user-1", "user@test.com", "sid-3", List.of());
        SseEmitter emitter = controller.stream(user, mock(HttpServletResponse.class));

        ((Runnable) callback(emitter, "timeoutCallback")).run();

        verify(registry).remove("sid-3", emitter);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onErrorRemovesEmitterFromRegistry() throws Exception {
        SpringSessionLiteUser user = new SpringSessionLiteUser("user-1", "user@test.com", "sid-4", List.of());
        SseEmitter emitter = controller.stream(user, mock(HttpServletResponse.class));

        ((Consumer<Throwable>) callback(emitter, "errorCallback")).accept(new RuntimeException("boom"));

        verify(registry).remove("sid-4", emitter);
    }
}
