package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.event.SpringSessionLiteSessionDestroyedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the backward-compatibility constructor added alongside the 2.1.0
 * {@code userId} field: source built against the pre-2.1.0, single-argument shape must keep
 * compiling and behaving sensibly (a {@code null} {@code userId}), while the new canonical
 * two-argument constructor carries both fields as given.
 */
class SpringSessionLiteSessionDestroyedEventTest {

    @Test
    void legacySingleArgConstructorDefaultsUserIdToNull() {
        SpringSessionLiteSessionDestroyedEvent event = new SpringSessionLiteSessionDestroyedEvent("sid-1");

        assertThat(event.userId()).isNull();
        assertThat(event.sessionId()).isEqualTo("sid-1");
    }

    @Test
    void canonicalTwoArgConstructorCarriesBothFields() {
        SpringSessionLiteSessionDestroyedEvent event = new SpringSessionLiteSessionDestroyedEvent("user-1", "sid-1");

        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.sessionId()).isEqualTo("sid-1");
    }
}
