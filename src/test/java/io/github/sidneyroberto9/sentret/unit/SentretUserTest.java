package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SentretUserTest {

    /**
     * Identity plus the two deadlines the filter already loaded, so status/heartbeat can report
     * remaining time without reading the row again. Roles stay with the host application.
     */
    @Test
    void principalCarriesIdentityAndDeadlines() {
        assertThat(SentretUser.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("userId", "email", "sessionId", "expiresAt", "lastAccessedAt");
    }

    @Test
    void accessorsReturnConstructorValues() {
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        Instant lastAccessedAt = Instant.parse("2029-12-31T23:00:00Z");

        SentretUser user = new SentretUser("u1", "u1@example.com", "s1", expiresAt, lastAccessedAt);

        assertThat(user.userId()).isEqualTo("u1");
        assertThat(user.email()).isEqualTo("u1@example.com");
        assertThat(user.sessionId()).isEqualTo("s1");
        assertThat(user.expiresAt()).isEqualTo(expiresAt);
        assertThat(user.lastAccessedAt()).isEqualTo(lastAccessedAt);
    }
}
