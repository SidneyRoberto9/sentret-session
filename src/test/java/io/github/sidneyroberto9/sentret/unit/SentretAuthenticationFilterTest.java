package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.domain.SentretSession;
import io.github.sidneyroberto9.sentret.security.SentretAuthenticationFilter;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The idle clock must be driven by real user activity, never by the client's own background
 * observation of the session. The bundled frontend client polls {@code GET /session/status} every
 * {@code statusPollIntervalMs} and holds {@code GET /session/stream} open regardless of activity;
 * if those requests touch {@code lastAccessedAt}, {@code maxIdle} can never elapse and an idle
 * session is never destroyed.
 *
 * <p>The filter cannot tell an observation request from a real one, so it touches for none of them:
 * activity is signalled explicitly and only by {@code POST /session/heartbeat}, which the client
 * fires from real DOM events (covered in {@code SentretServiceTest} and the controller
 * integration test). Everything here therefore asserts the same rule from a different angle — the
 * filter authenticates, and never writes.
 */
class SentretAuthenticationFilterTest {

    private SentretProperties properties;
    private SentretSessionStore store;
    private SentretCookieManager cookieManager;
    private SentretAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(2));
        store = mock(SentretSessionStore.class);
        cookieManager = new SentretCookieManager(properties);

        SentretService service = new SentretService(store, properties, mock(ApplicationEventPublisher.class), cookieManager);

        filter = new SentretAuthenticationFilter(service, cookieManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * A session idle for the given duration. At 90s it is past the effective throttle
     * (maxIdle/2 = 60s), so a touch would fire on any request that counted as activity — which is
     * exactly what these tests assert does not happen.
     */
    private SentretSession sessionIdleFor(Duration idle, Instant now) {
        SentretSession session = new SentretSession();
        session.setSessionId("sid");
        session.setUserId("user-1");
        session.setEmail("user@test.com");
        session.setCreatedAt(now.minus(Duration.ofHours(1)));
        session.setExpiresAt(now.plus(Duration.ofHours(1)));
        session.setLastAccessedAt(now.minus(idle));

        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));

        return session;
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setCookies(new Cookie(cookieManager.cookieName(), "sid"));
        return request;
    }

    private void doFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    // --- no request, of any kind, resets the idle clock ---

    @Test
    void statusPollDoesNotResetIdleClock() throws Exception {
        Instant now = Instant.now();
        Instant lastAccessed = now.minus(Duration.ofSeconds(90));
        SentretSession session = sessionIdleFor(Duration.ofSeconds(90), now);

        doFilter(request("GET", "/session/status"));

        assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessed);
        verify(store, never()).save(any());
    }

    @Test
    void streamDoesNotResetIdleClock() throws Exception {
        Instant now = Instant.now();
        Instant lastAccessed = now.minus(Duration.ofSeconds(90));
        SentretSession session = sessionIdleFor(Duration.ofSeconds(90), now);

        doFilter(request("GET", "/session/stream"));

        assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessed);
        verify(store, never()).save(any());
    }

    /**
     * A request to the consuming app's own API is not an activity signal either: only an explicit
     * heartbeat is. Otherwise any background refetch the app happens to make would silently do what
     * the {@code /status} poll used to.
     */
    @Test
    void regularRequestDoesNotResetIdleClock() throws Exception {
        Instant now = Instant.now();
        Instant lastAccessed = now.minus(Duration.ofSeconds(90));
        SentretSession session = sessionIdleFor(Duration.ofSeconds(90), now);

        doFilter(request("GET", "/api/documents"));

        assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessed);
        verify(store, never()).save(any());
    }

    /**
     * Even the heartbeat does not touch <em>here</em> — the filter has no idea what it is routing
     * to. The controller calls {@code touch()} explicitly once this filter has authenticated.
     */
    @Test
    void heartbeatDoesNotResetIdleClockInTheFilter() throws Exception {
        Instant now = Instant.now();
        Instant lastAccessed = now.minus(Duration.ofSeconds(90));
        SentretSession session = sessionIdleFor(Duration.ofSeconds(90), now);

        doFilter(request("POST", "/session/heartbeat"));

        assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessed);
        verify(store, never()).save(any());
    }

    // --- not touching must not mean not authenticating ---

    @Test
    void statusPollStillAuthenticates() throws Exception {
        sessionIdleFor(Duration.ofSeconds(90), Instant.now());

        doFilter(request("GET", "/session/status"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    /**
     * The whole point: a poll must not rescue a session that has already gone idle.
     */
    @Test
    void statusPollDoesNotRescueIdleExpiredSession() throws Exception {
        sessionIdleFor(Duration.ofMinutes(3), Instant.now());

        doFilter(request("GET", "/session/status"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // --- authorities: bare role names get a ROLE_ prefix, already-prefixed ones are left alone ---

    @Test
    void authenticatesWithBothBareAndPrefixedRoleNames() throws Exception {
        SentretSession session = sessionIdleFor(Duration.ofSeconds(1), Instant.now());
        session.setRoles("ADMIN,ROLE_SUPPORT");

        doFilter(request("GET", "/api/documents"));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_SUPPORT");
    }
}
