package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.hub.SentretHubStatusService;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionConfigResponse;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionStatusResponse;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SentretHubStatusServiceTest {

    private static final Offset<Long> FIVE_SECONDS = Offset.offset(5_000L);

    private SentretProperties properties;
    private SentretHubStatusService service;

    @BeforeEach
    void setUp() {
        properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.getHub().setEnabled(true);
        properties.getHub().setLoginUrl("https://login.example.com");
        service = new SentretHubStatusService(properties);
    }

    private static SentretUser user(Instant expiresAt, Instant lastAccessedAt) {
        return new SentretUser("user-1", "user@test.com", "sid", expiresAt, lastAccessedAt);
    }

    @Test
    void anonymousStatusCarriesOnlyTheConfig() {
        SessionStatusResponse status = service.status(null);

        assertThat(status.authenticated()).isFalse();
        assertThat(status.userId()).isNull();
        assertThat(status.absoluteRemainingMs()).isNull();
        assertThat(status.idleRemainingMs()).isNull();
        assertThat(status.config()).isNotNull();
    }

    @Test
    void authenticatedStatusComputesBothDeadlinesFromThePrincipal() {
        Instant now = Instant.now();

        SessionStatusResponse status = service.status(user(now.plus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(4))));

        assertThat(status.authenticated()).isTrue();
        assertThat(status.userId()).isEqualTo("user-1");
        assertThat(status.email()).isEqualTo("user@test.com");
        assertThat(status.absoluteRemainingMs()).isCloseTo(Duration.ofMinutes(30).toMillis(), FIVE_SECONDS);
        assertThat(status.idleRemainingMs()).isCloseTo(Duration.ofMinutes(6).toMillis(), FIVE_SECONDS);
    }

    @Test
    void idleRemainingIsNullWhenMaxIdleDisabled() {
        properties.setMaxIdle(Duration.ZERO);
        Instant now = Instant.now();

        SessionStatusResponse status = service.status(user(now.plus(Duration.ofMinutes(30)), now));

        assertThat(status.idleRemainingMs()).isNull();
    }

    @Test
    void remainingTimesNeverGoNegative() {
        Instant now = Instant.now();

        SessionStatusResponse status = service.status(user(now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofMinutes(20))));

        assertThat(status.absoluteRemainingMs()).isZero();
        assertThat(status.idleRemainingMs()).isZero();
    }

    /** Only what the npm client reads: ttlMs, maxIdleMs, logoutUrl and redirectAfterExpiryUrl are gone. */
    @Test
    void configEchoesOnlyWhatTheClientReads() {
        assertThat(service.status(null).config())
                .isEqualTo(new SessionConfigResponse(60_000L, 30_000L, 60_000L, "https://login.example.com"));
    }
}
