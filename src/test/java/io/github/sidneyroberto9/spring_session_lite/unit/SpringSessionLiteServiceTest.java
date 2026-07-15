package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.event.SpringSessionLiteSessionRenewedEvent;
import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteCookieManager;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteIpHasher;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteIpResolver;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteSessionRemaining;
import io.github.sidneyroberto9.spring_session_lite.store.SpringSessionLiteSessionStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpringSessionLiteServiceTest {

    private SpringSessionLiteProperties properties;
    private SpringSessionLiteSessionStore store;
    private ApplicationEventPublisher eventPublisher;
    private SpringSessionLiteIpHasher ipHasher;
    private SpringSessionLiteCookieManager cookieManager;
    private SpringSessionLiteService service;

    @BeforeEach
    void setUp() {
        properties = new SpringSessionLiteProperties();
        store = mock(SpringSessionLiteSessionStore.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        ipHasher = new SpringSessionLiteIpHasher(properties);
        cookieManager = new SpringSessionLiteCookieManager(properties);
        SpringSessionLiteIpResolver ipResolver = new SpringSessionLiteIpResolver(properties);

        service = new SpringSessionLiteService(ipHasher, store, properties, ipResolver, eventPublisher, cookieManager);
    }

    private SpringSessionLiteSession sessionFor(String ip, Instant now) {
        SpringSessionLiteSession session = new SpringSessionLiteSession();
        session.setSessionId("sid");
        session.setUserId("user-1");
        session.setEmail("user@test.com");
        session.setIpHash(ipHasher.hash(ip));
        session.setCreatedAt(now.minus(Duration.ofHours(1)));
        session.setExpiresAt(now.plus(Duration.ofHours(1)));
        session.setLastAccessedAt(now);
        return session;
    }

    private MockHttpServletRequest request(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    // --- login(): 4-arg overload delegates to the 5-arg one with no roles ---

    @Test
    void loginWithoutRolesDelegatesToRolesOverloadWithEmptyRoles() {
        MockHttpServletRequest request = request("203.0.113.30");
        MockHttpServletResponse response = new MockHttpServletResponse();

        SpringSessionLiteUser user = service.login("user-1", "user@test.com", request, response);

        assertThat(user.roles()).isEmpty();
        verify(store).save(any());
    }

    // --- logoutAll() ---

    @Test
    void logoutAllDeletesEverySessionForUser() {
        service.logoutAll("user-1");

        verify(store).deleteByUserId("user-1");
    }

    // --- deleteExpired() ---

    @Test
    void deleteExpiredDelegatesToStoreWithCurrentInstant() {
        service.deleteExpired();

        verify(store).deleteExpired(any());
    }

    // --- touch(): early-return guard when nothing would change ---

    @Test
    void touchIsNoOpWhenNoTrackingIsEnabled() {
        properties.setUpdateLastAccessed(false);
        properties.setSlidingExpiration(false);
        // maxIdle defaults to Duration.ZERO -> isIdleEnabled() is false too
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.31", now);
        Instant lastAccessed = now.minus(Duration.ofHours(1));
        session.setLastAccessedAt(lastAccessed);
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        service.touch("sid");

        assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessed);
        verify(store, never()).save(any());
    }

    // --- touch(): sliding expiration pushes expiresAt out too ---

    @Test
    void touchSlidesExpiresAtWhenSlidingExpirationEnabled() {
        properties.setTtl(Duration.ofHours(1));
        properties.setSlidingExpiration(true);
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.32", now);
        session.setExpiresAt(now.plus(Duration.ofMinutes(1)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        service.touch("sid");

        assertThat(session.getExpiresAt()).isAfter(now.plus(Duration.ofMinutes(55)));
        verify(store).save(session);
    }

    // --- touch(): sliding expiration alone (no updateLastAccessed, no idle) still writes ---

    @Test
    void touchSlidesExpiresAtWithoutTouchingLastAccessedWhenOnlySlidingExpirationEnabled() {
        properties.setUpdateLastAccessed(false);
        properties.setSlidingExpiration(true);
        properties.setTtl(Duration.ofHours(1));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.33", now);
        Instant lastAccessed = now.minus(Duration.ofMinutes(10));
        session.setLastAccessedAt(lastAccessed);
        session.setExpiresAt(now.plus(Duration.ofMinutes(1)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        service.touch("sid");

        assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessed);
        assertThat(session.getExpiresAt()).isAfter(now.plus(Duration.ofMinutes(55)));
        verify(store).save(session);
    }

    // --- touch(): idle-enabled alone (no updateLastAccessed, no sliding) still resets the clock ---

    @Test
    void touchResetsLastAccessedWhenOnlyIdleEnabledEvenWithUpdateLastAccessedDisabled() {
        properties.setUpdateLastAccessed(false);
        properties.setSlidingExpiration(false);
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.34", now);
        Instant lastAccessed = now.minus(Duration.ofMinutes(5));
        session.setLastAccessedAt(lastAccessed);
        Instant expiresAt = session.getExpiresAt();
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        service.touch("sid");

        assertThat(session.getLastAccessedAt()).isAfter(now.minus(Duration.ofSeconds(5)));
        assertThat(session.getExpiresAt()).isEqualTo(expiresAt);
        verify(store).save(session);
    }

    // --- isIdleEnabled(): null maxIdle is treated as disabled, same as zero/negative ---

    @Test
    void remainingTreatsNullMaxIdleAsDisabled() {
        properties.setMaxIdle(null);
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.35", now);
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteSessionRemaining> result = service.remaining("sid");

        assertThat(result).isPresent();
        assertThat(result.get().idleRemainingMs()).isNull();
    }

    // --- logout(request, response): no cookie present ---

    @Test
    void logoutWithRequestAndResponseSkipsSessionLookupWhenNoCookiePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.logout(request, response);

        verify(store, never()).findBySessionId(any());
        assertThat(response.getHeader("Set-Cookie")).isNotNull();
    }

    // --- joinRoles()/splitRoles(): null vs blank vs populated, and blank-entry filtering ---

    @Test
    void loginWithNullRolesStoresNullRoles() {
        MockHttpServletRequest request = request("203.0.113.36");
        MockHttpServletResponse response = new MockHttpServletResponse();

        SpringSessionLiteUser user = service.login("user-1", "user@test.com", null, request, response);

        assertThat(user.roles()).isEmpty();
    }

    @Test
    void validateSplitsBlankStoredRolesAsEmptyList() {
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.37", now);
        session.setRoles("   ");
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteUser> result = service.validate("sid", request("203.0.113.37"));

        assertThat(result).isPresent();
        assertThat(result.get().roles()).isEmpty();
    }

    @Test
    void validateFiltersOutBlankEntriesFromStoredRoles() {
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.38", now);
        session.setRoles("ADMIN,,USER");
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteUser> result = service.validate("sid", request("203.0.113.38"));

        assertThat(result).isPresent();
        assertThat(result.get().roles()).containsExactly("ADMIN", "USER");
    }

    // --- validate(): inactivity expiration ---

    @Test
    void validateReturnsEmptyWhenIdleExceedsMaxIdle() {
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.1", now);
        session.setLastAccessedAt(now.minus(Duration.ofMinutes(11)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteUser> result = service.validate("sid", request("203.0.113.1"));

        assertThat(result).isEmpty();
        verify(store, never()).save(any());
    }

    @Test
    void validateReturnsPresentButDoesNotResetIdleClockWithinMaxIdle() {
        // A session inside the maxIdle window is kept alive, but validating it is NOT activity:
        // lastAccessedAt must not move. Otherwise the client's own /status poll (every
        // statusPollInterval, well under the effective throttle) would refresh the idle deadline
        // forever and maxIdle could never elapse. Only touch() -- i.e. POST /session/heartbeat --
        // resets the clock.
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.2", now);
        Instant lastAccessed = now.minus(Duration.ofMinutes(9));
        session.setLastAccessedAt(lastAccessed);
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteUser> result = service.validate("sid", request("203.0.113.2"));

        assertThat(result).isPresent();
        assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessed);
        verify(store, never()).save(session);
    }

    @Test
    void touchResetsIdleClock() {
        // The heartbeat path: the one and only activity signal.
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.2", now);
        session.setLastAccessedAt(now.minus(Duration.ofMinutes(9)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        service.touch("sid");

        assertThat(session.getLastAccessedAt()).isAfter(now.minus(Duration.ofSeconds(5)));
        verify(store).save(session);
    }

    @Test
    void touchIsNoOpWhenSessionNotFound() {
        when(store.findBySessionId("gone")).thenReturn(Optional.empty());

        service.touch("gone");

        verify(store, never()).save(any());
    }

    /**
     * A poll cannot rescue a session that has gone idle, no matter how often it runs: the reproduction
     * of the bug this behaviour exists to prevent.
     */
    @Test
    void repeatedValidationNeverExtendsIdleWindow() {
        properties.setMaxIdle(Duration.ofMinutes(2));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.2", now);
        Instant lastAccessed = now.minus(Duration.ofSeconds(90));
        session.setLastAccessedAt(lastAccessed);
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        for (int i = 0; i < 10; i++) {
            service.validate("sid", request("203.0.113.2"));
        }

        assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessed);
        verify(store, never()).save(any());
    }

    @Test
    void validateIgnoresInactivityWhenMaxIdleDisabledByDefault() {
        // maxIdle defaults to Duration.ZERO (disabled), preserving pre-2.1 behavior exactly.
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.3", now);
        session.setLastAccessedAt(now.minus(Duration.ofHours(10)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        assertThat(service.validate("sid", request("203.0.113.3"))).isPresent();
    }

    @Test
    void validateIgnoresInactivityWhenMaxIdleNegative() {
        properties.setMaxIdle(Duration.ofMinutes(-5));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.4", now);
        session.setLastAccessedAt(now.minus(Duration.ofHours(10)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        assertThat(service.validate("sid", request("203.0.113.4"))).isPresent();
    }

    @Test
    void validateFallsBackToCreatedAtWhenLastAccessedAtNullAndIdleExceeded() {
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.5", now);
        session.setLastAccessedAt(null);
        session.setCreatedAt(now.minus(Duration.ofMinutes(11)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        assertThat(service.validate("sid", request("203.0.113.5"))).isEmpty();
    }

    @Test
    void validateFallsBackToCreatedAtWhenLastAccessedAtNullAndWithinIdle() {
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.6", now);
        session.setLastAccessedAt(null);
        session.setCreatedAt(now.minus(Duration.ofMinutes(1)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        assertThat(service.validate("sid", request("203.0.113.6"))).isPresent();
    }

    /**
     * A heartbeat is never throttled away. This is the bug that logged active users out: the user
     * moves the mouse while the inactivity warning is up, the client sends the heartbeat, and the
     * server silently drops it because the previous touch was recent — so `lastAccessedAt` never
     * moved, the idle-watch kept pushing `warning`, and the countdown ran to zero with the user
     * sitting right there. The client already throttles heartbeats to `heartbeat-interval`; there
     * is nothing left for this layer to protect against.
     */
    @Test
    void touchAlwaysWritesEvenImmediatelyAfterAPreviousTouch() {
        properties.setMaxIdle(Duration.ofMinutes(4));
        properties.setLastAccessedThrottle(Duration.ofMinutes(5));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.7", now);
        session.setLastAccessedAt(now.minus(Duration.ofSeconds(1)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        service.touch("sid");

        assertThat(session.getLastAccessedAt()).isAfter(now.minus(Duration.ofSeconds(1)));
        verify(store).save(session);
    }

    @Test
    void touchIsNotCappedByLastAccessedThrottle() {
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.setLastAccessedThrottle(Duration.ofHours(1));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.7", now);
        session.setLastAccessedAt(now.minus(Duration.ofSeconds(5)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        service.touch("sid");

        assertThat(session.getLastAccessedAt()).isAfter(now.minus(Duration.ofSeconds(5)));
        verify(store).save(session);
    }

    /**
     * The scenario end to end: a session one second away from idle expiry is fully rescued by a
     * single heartbeat.
     */
    @Test
    void touchRescuesSessionAboutToIdleExpire() {
        properties.setMaxIdle(Duration.ofMinutes(2));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.7", now);
        session.setLastAccessedAt(now.minus(Duration.ofSeconds(119)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        service.touch("sid");

        assertThat(service.validate("sid", request("203.0.113.7"))).isPresent();
        assertThat(service.remaining("sid").orElseThrow().idleRemainingMs())
                .isGreaterThan(Duration.ofSeconds(115).toMillis());
    }

    // --- renew(sessionId) ---

    @Test
    void renewBySessionIdResetsExpiresAtAndLastAccessedAtAndPublishesEvent() {
        properties.setTtl(Duration.ofHours(2));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.9", now);
        session.setExpiresAt(now.plus(Duration.ofMinutes(1)));
        session.setLastAccessedAt(now.minus(Duration.ofMinutes(30)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteUser> result = service.renew("sid");

        assertThat(result).isPresent();
        assertThat(session.getExpiresAt()).isAfter(now.plus(Duration.ofHours(1)));
        assertThat(session.getLastAccessedAt()).isAfter(now.minus(Duration.ofSeconds(5)));
        verify(store).save(session);

        ArgumentCaptor<SpringSessionLiteSessionRenewedEvent> captor = ArgumentCaptor.forClass(SpringSessionLiteSessionRenewedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("sid");
        assertThat(captor.getValue().userId()).isEqualTo("user-1");
    }

    @Test
    void renewBySessionIdReturnsEmptyWhenSessionNotFound() {
        when(store.findBySessionId("missing")).thenReturn(Optional.empty());

        assertThat(service.renew("missing")).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
    }

    // --- renew(request, response) ---

    @Test
    void renewWithRequestAndResponseRewritesCookieMaxAge() {
        properties.setTtl(Duration.ofMinutes(30));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.10", now);
        session.setExpiresAt(now.plus(Duration.ofMinutes(2)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(cookieManager.cookieName(), "sid"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        Optional<SpringSessionLiteUser> result = service.renew(request, response);

        assertThat(result).isPresent();
        assertThat(session.getExpiresAt()).isAfter(now.plus(Duration.ofMinutes(29)));

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("Max-Age=1800");
    }

    @Test
    void renewWithRequestAndResponseReturnsEmptyWhenNoCookiePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(service.renew(request, response)).isEmpty();
        verifyNoInteractions(store);
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    // --- remaining() ---

    @Test
    void remainingReturnsEmptyWhenSessionNotFound() {
        when(store.findBySessionId("missing")).thenReturn(Optional.empty());

        assertThat(service.remaining("missing")).isEmpty();
    }

    @Test
    void remainingReturnsNullIdleRemainingWhenMaxIdleDisabledByDefault() {
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.20", now);
        session.setExpiresAt(now.plus(Duration.ofMinutes(30)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteSessionRemaining> result = service.remaining("sid");

        assertThat(result).isPresent();
        assertThat(result.get().absoluteRemainingMs()).isCloseTo(Duration.ofMinutes(30).toMillis(), org.assertj.core.data.Offset.offset(5_000L));
        assertThat(result.get().idleRemainingMs()).isNull();
    }

    @Test
    void remainingComputesIdleRemainingWhenMaxIdleEnabled() {
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.21", now);
        session.setExpiresAt(now.plus(Duration.ofMinutes(30)));
        session.setLastAccessedAt(now.minus(Duration.ofMinutes(4)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteSessionRemaining> result = service.remaining("sid");

        assertThat(result).isPresent();
        // idle deadline = lastAccessedAt(now-4m) + maxIdle(10m) = now+6m from "now"
        assertThat(result.get().idleRemainingMs()).isCloseTo(Duration.ofMinutes(6).toMillis(), org.assertj.core.data.Offset.offset(5_000L));
    }

    @Test
    void remainingFallsBackToCreatedAtWhenLastAccessedAtNullAndMaxIdleEnabled() {
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.22", now);
        session.setLastAccessedAt(null);
        session.setCreatedAt(now.minus(Duration.ofMinutes(2)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteSessionRemaining> result = service.remaining("sid");

        assertThat(result).isPresent();
        // idle deadline = createdAt(now-2m) + maxIdle(10m) = now+8m from "now"
        assertThat(result.get().idleRemainingMs()).isCloseTo(Duration.ofMinutes(8).toMillis(), org.assertj.core.data.Offset.offset(5_000L));
    }

    @Test
    void remainingClampsToZeroWhenAlreadyPastExpiryOrIdleDeadline() {
        properties.setMaxIdle(Duration.ofMinutes(10));
        Instant now = Instant.now();
        SpringSessionLiteSession session = sessionFor("203.0.113.23", now);
        session.setExpiresAt(now.minus(Duration.ofMinutes(1)));
        session.setLastAccessedAt(now.minus(Duration.ofMinutes(20)));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteSessionRemaining> result = service.remaining("sid");

        assertThat(result).isPresent();
        assertThat(result.get().absoluteRemainingMs()).isZero();
        assertThat(result.get().idleRemainingMs()).isZero();
    }
}
