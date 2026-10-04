package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.event.SentretSessionCreatedEvent;
import io.github.sidneyroberto9.sentret.event.SentretSessionDestroyedEvent;
import io.github.sidneyroberto9.sentret.event.SentretSessionRenewedEvent;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.store.SentretSession;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import jakarta.servlet.http.Cookie;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SentretServiceTest {

    private static final Offset<Long> FIVE_SECONDS = Offset.offset(5_000L);
    private static final String SID = "AbCdEfGhIjKlMnOpQr_-";
    private static final String UNKNOWN = "ZzZzZzZzZzZzZzZzZz00";

    private SentretProperties properties;
    private SentretSessionStore store;
    private ApplicationEventPublisher eventPublisher;
    private SentretCookieManager cookieManager;
    private SentretService service;

    @BeforeEach
    void setUp() {
        properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.getHub().setEnabled(true);
        store = mock(SentretSessionStore.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        cookieManager = new SentretCookieManager(properties);
        service = new SentretService(store, properties, eventPublisher, cookieManager);
        when(store.updateLastAccessedAt(any(), any())).thenReturn(true);
        when(store.updateExpiresAt(any(), any(), any())).thenReturn(true);
    }

    private void stored(Instant expiresAt, Instant lastAccessedAt) {
        SentretSession session = new SentretSession(
                SID, "user-1", "user@test.com", lastAccessedAt.minus(Duration.ofHours(1)), expiresAt, lastAccessedAt);
        when(store.findBySessionId(SID)).thenReturn(Optional.of(session));
    }

    private static SentretUser user(Instant expiresAt, Instant lastAccessedAt) {
        return new SentretUser("user-1", "user@test.com", SID, expiresAt, lastAccessedAt);
    }

    // --- login ---

    @Test
    void loginInsertsSessionWritesCookieAndPublishesEvent() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SentretUser user = service.login("user-1", "user@test.com", response);

        ArgumentCaptor<SentretSession> captor = ArgumentCaptor.forClass(SentretSession.class);
        verify(store).insert(captor.capture());
        SentretSession inserted = captor.getValue();
        assertThat(inserted.sessionId()).isEqualTo(user.sessionId());
        assertThat(inserted.userId()).isEqualTo("user-1");
        assertThat(inserted.expiresAt()).isEqualTo(inserted.createdAt().plus(properties.getTtl()));
        assertThat(inserted.lastAccessedAt()).isEqualTo(inserted.createdAt());
        assertThat(response.getHeader("Set-Cookie")).contains(user.sessionId());
        verify(eventPublisher).publishEvent(any(SentretSessionCreatedEvent.class));
    }

    @Test
    void loginGeneratesTwentyCharUrlSafeUniqueSessionIds() {
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            ids.add(service.login("user-1", "user@test.com", new MockHttpServletResponse()).sessionId());
        }

        assertThat(ids).hasSize(1_000).allMatch(id -> id.matches("[A-Za-z0-9_-]{20}"));
    }

    // --- validate ---

    @Test
    void validateReturnsUserCarryingTheSessionDeadlines() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(1)));

        SentretUser user = service.validate(SID).orElseThrow();

        assertThat(user.userId()).isEqualTo("user-1");
        assertThat(user.expiresAt()).isEqualTo(now.plus(Duration.ofHours(1)));
        assertThat(user.lastAccessedAt()).isEqualTo(now.minus(Duration.ofMinutes(1)));
    }

    @Test
    void validateReturnsEmptyForUnknownSession() {
        when(store.findBySessionId(UNKNOWN)).thenReturn(Optional.empty());

        assertThat(service.validate(UNKNOWN)).isEmpty();
    }

    /** Anything that is not a 20-char Base64 URL id is rejected before reaching the database. */
    @Test
    void nullIdsAreTreatedAsUnknownSessions() {
        assertThat(service.validate(null)).isEmpty();
        service.logout((String) null);

        verifyNoInteractions(store);
    }

    @Test
    void validateRejectsMalformedIdsWithoutTouchingTheStore() {
        assertThat(service.validate("x".repeat(100))).isEmpty();
        assertThat(service.validate("short")).isEmpty();
        assertThat(service.validate("AbCdEfGhIjKlMnOpQr_!")).isEmpty();
        assertThat(service.validate("")).isEmpty();

        verifyNoInteractions(store);
    }

    /**
     * MySQL, MariaDB and SQL Server compare VARCHAR case-insensitively by default, so a lookup can
     * return a row whose id differs only in case. That must not authenticate.
     */
    @Test
    void validateRejectsARowWhoseIdDiffersOnlyInCase() {
        Instant now = Instant.now();
        String sameLettersOtherCase = SID.toLowerCase();
        SentretSession stored = new SentretSession(SID, "user-1", "user@test.com", now, now.plus(Duration.ofHours(1)), now);
        when(store.findBySessionId(sameLettersOtherCase)).thenReturn(Optional.of(stored));

        assertThat(service.validate(sameLettersOtherCase)).isEmpty();
    }

    @Test
    void validateReturnsEmptyPastAbsoluteExpiry() {
        Instant now = Instant.now();
        stored(now.minus(Duration.ofSeconds(1)), now);

        assertThat(service.validate(SID)).isEmpty();
    }

    @Test
    void validateReturnsEmptyWhenIdleExceedsMaxIdle() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(11)));

        assertThat(service.validate(SID)).isEmpty();
    }

    @Test
    void validateIgnoresIdleWhenMaxIdleIsZero() {
        properties.setMaxIdle(Duration.ZERO);
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofHours(5)));

        assertThat(service.validate(SID)).isPresent();
    }

    @Test
    void validateIgnoresIdleWhenMaxIdleIsNull() {
        properties.setMaxIdle(null);
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofHours(5)));

        assertThat(service.validate(SID)).isPresent();
    }

    /**
     * Validating is not activity. The client polls the status every 30s whether or not the user is
     * there; if validation wrote last_accessed_at, max-idle could never elapse.
     */
    @Test
    void repeatedValidationNeverWrites() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofSeconds(90)));

        for (int i = 0; i < 10; i++) {
            service.validate(SID);
        }

        verify(store, times(10)).findBySessionId(SID);
        verifyNoMoreInteractions(store);
    }

    // --- touch (heartbeat) ---

    @Test
    void touchWritesWithOneUpdateAndReturnsTheRefreshedUser() {
        Instant now = Instant.now();
        SentretUser user = user(now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(9)));

        SentretUser touched = service.touch(user).orElseThrow();

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(store).updateLastAccessedAt(eq(SID), at.capture());
        verifyNoMoreInteractions(store);
        assertThat(touched.lastAccessedAt()).isEqualTo(at.getValue()).isAfter(now.minusSeconds(1));
        assertThat(touched.expiresAt()).isEqualTo(user.expiresAt());
    }

    /**
     * Never throttled. The client already throttles heartbeats to heartbeat-interval; dropping one
     * here is the bug that logged active users out while the warning was on screen.
     */
    @Test
    void touchWritesEvenRightAfterAPreviousTouch() {
        Instant now = Instant.now();

        service.touch(user(now.plus(Duration.ofHours(1)), now.minusSeconds(1)));

        verify(store).updateLastAccessedAt(eq(SID), any());
    }

    /** The row was deleted between the filter and the heartbeat (a concurrent logout): no session to touch. */
    @Test
    void touchOfASessionDeletedMeanwhileIsEmpty() {
        when(store.updateLastAccessedAt(any(), any())).thenReturn(false);

        assertThat(service.touch(user(Instant.now().plus(Duration.ofHours(1)), Instant.now()))).isEmpty();
    }

    @Test
    void touchIsNoOpWhenIdleDisabled() {
        properties.setMaxIdle(Duration.ZERO);
        SentretUser user = user(Instant.now().plus(Duration.ofHours(1)), Instant.now());

        assertThat(service.touch(user)).containsSame(user);
        verifyNoInteractions(store);
    }




    // --- renew ---

    @Test
    void renewResetsBothDeadlinesAndPublishesEvent() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofMinutes(1)), now.minus(Duration.ofMinutes(5)));

        SentretUser renewed = service.renew(SID).orElseThrow();

        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> lastAccessedAt = ArgumentCaptor.forClass(Instant.class);
        verify(store).updateExpiresAt(eq(SID), expiresAt.capture(), lastAccessedAt.capture());
        assertThat(expiresAt.getValue()).isEqualTo(lastAccessedAt.getValue().plus(properties.getTtl()));
        assertThat(renewed.expiresAt()).isEqualTo(expiresAt.getValue());
        assertThat(renewed.lastAccessedAt()).isEqualTo(lastAccessedAt.getValue());

        ArgumentCaptor<SentretSessionRenewedEvent> event = ArgumentCaptor.forClass(SentretSessionRenewedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo("user-1");
        assertThat(event.getValue().sessionId()).isEqualTo(SID);
    }

    @Test
    void renewOfASessionDeletedMeanwhileIsEmptyAndLeavesTheCookieAlone() {
        when(store.updateExpiresAt(any(), any(), any())).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(service.renew(user(Instant.now().plus(Duration.ofHours(1)), Instant.now()), response)).isEmpty();
        assertThat(response.getHeader("Set-Cookie")).isNull();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void renewOfUnknownSessionReturnsEmptyAndPublishesNothing() {
        when(store.findBySessionId(UNKNOWN)).thenReturn(Optional.empty());

        assertThat(service.renew(UNKNOWN)).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void renewDoesNotResurrectAnIdleExpiredSession() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(11)));

        assertThat(service.renew(SID)).isEmpty();
        verify(store, never()).updateExpiresAt(any(), any(), any());
    }

    /** The hub renew: the filter already validated the session, so no second read. */
    @Test
    void renewOfThePrincipalRewritesTheCookieWithoutReadingTheStore() {
        properties.setTtl(Duration.ofMinutes(30));
        Instant now = Instant.now();
        MockHttpServletResponse response = new MockHttpServletResponse();

        SentretUser renewed = service.renew(user(now.plus(Duration.ofMinutes(2)), now), response).orElseThrow();

        verify(store).updateExpiresAt(eq(SID), eq(renewed.expiresAt()), eq(renewed.lastAccessedAt()));
        verify(store, never()).findBySessionId(any());
        assertThat(renewed.expiresAt()).isEqualTo(renewed.lastAccessedAt().plus(Duration.ofMinutes(30)));
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=1800");
        verify(eventPublisher).publishEvent(any(SentretSessionRenewedEvent.class));
    }

    // --- logout ---

    @Test
    void logoutDeletesTheSessionAndPublishesEventWithUserId() {
        Instant now = Instant.now();
        stored(now.plus(Duration.ofHours(1)), now);

        service.logout(SID);

        verify(store).deleteBySessionId(SID);
        ArgumentCaptor<SentretSessionDestroyedEvent> event = ArgumentCaptor.forClass(SentretSessionDestroyedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo("user-1");
        assertThat(event.getValue().sessionId()).isEqualTo(SID);
    }

    /** Same guard as validate: on a case-folding collation a look-alike id must not log someone out. */
    @Test
    void logoutIgnoresARowWhoseIdDiffersOnlyInCase() {
        Instant now = Instant.now();
        String sameLettersOtherCase = SID.toLowerCase();
        SentretSession stored = new SentretSession(SID, "user-1", "user@test.com", now, now.plus(Duration.ofHours(1)), now);
        when(store.findBySessionId(sameLettersOtherCase)).thenReturn(Optional.of(stored));

        service.logout(sameLettersOtherCase);

        verify(store, never()).deleteBySessionId(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void logoutOfAMalformedIdNeverReachesTheStore() {
        service.logout("x".repeat(100));

        verifyNoInteractions(store);
    }

    @Test
    void logoutOfUnknownSessionPublishesNothing() {
        when(store.findBySessionId(UNKNOWN)).thenReturn(Optional.empty());

        service.logout(UNKNOWN);

        verify(store, never()).deleteBySessionId(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void logoutWithRequestClearsTheCookieEvenWithoutSession() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.logout(new MockHttpServletRequest(), response);

        verify(store, never()).findBySessionId(any());
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void logoutAllDeletesEverySessionOfTheUser() {
        service.logoutAll("user-1");

        verify(store).deleteByUserId("user-1");
    }

    /** The purge is housekeeping: a lock timeout or deadlock there must not cost the user a login. */
    @Test
    void loginSucceedsEvenWhenThePurgeFails() {
        doThrow(new QueryTimeoutException("lock wait timeout")).when(store).deleteExpired(any());
        MockHttpServletResponse response = new MockHttpServletResponse();

        SentretUser user = service.login("user-1", "user@test.com", response);

        verify(store).insert(any());
        assertThat(response.getHeader("Set-Cookie")).contains(user.sessionId());
    }

    /** No scheduler: expired rows are purged on login, which is rare and hits the expires_at index. */
    @Test
    void loginPurgesExpiredSessionsBeforeInsertingTheNewOne() {
        service.login("user-1", "user@test.com", new MockHttpServletResponse());

        InOrder order = inOrder(store);
        order.verify(store).deleteExpired(any());
        order.verify(store).insert(any());
    }
}
