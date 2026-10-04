package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.event.SentretSessionDestroyedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the backward-compatibility constructor added alongside the 2.1.0
 * {@code userId} field: source built against the pre-2.1.0, single-argument shape must keep
 * compiling and behaving sensibly (a {@code null} {@code userId}), while the new canonical
 * two-argument constructor carries both fields as given.
 */
class SentretSessionDestroyedEventTest {

    @Test
    void legacySingleArgConstructorDefaultsUserIdToNull() {
        SentretSessionDestroyedEvent event = new SentretSessionDestroyedEvent("sid-1");

        assertThat(event.userId()).isNull();
        assertThat(event.sessionId()).isEqualTo("sid-1");
    }

    @Test
    void canonicalTwoArgConstructorCarriesBothFields() {
        SentretSessionDestroyedEvent event = new SentretSessionDestroyedEvent("user-1", "sid-1");

        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.sessionId()).isEqualTo("sid-1");
    }
}
