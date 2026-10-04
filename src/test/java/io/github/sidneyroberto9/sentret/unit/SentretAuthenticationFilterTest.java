package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.security.SentretAuthenticationFilter;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.store.SentretSession;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The filter authenticates and never writes. Activity is signalled only by the heartbeat endpoint,
 * so neither the client's status poll nor the host application's own background requests can keep
 * an idle session alive.
 */
class SentretAuthenticationFilterTest {

    private SentretSessionStore store;
    private SentretCookieManager cookieManager;
    private SentretAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(2));
        properties.getHub().setEnabled(true);
        store = mock(SentretSessionStore.class);
        cookieManager = new SentretCookieManager(properties);
        SentretService service = new SentretService(store, properties, mock(ApplicationEventPublisher.class), cookieManager);
        filter = new SentretAuthenticationFilter(service, cookieManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void storedSessionIdleFor(Duration idle) {
        Instant now = Instant.now();
        SentretSession session = new SentretSession(
                "sid", "user-1", "user@test.com", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), now.minus(idle));
        when(store.findBySessionId("sid")).thenReturn(Optional.of(session));
    }

    private MockHttpServletResponse doFilter(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setCookies(new Cookie(cookieManager.cookieName(), "sid"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void validSessionAuthenticatesTheUserWithNoAuthorities() throws Exception {
        storedSessionIdleFor(Duration.ofSeconds(1));

        doFilter("GET", "/api/documents");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isInstanceOf(SentretUser.class);
        assertThat(((SentretUser) auth.getPrincipal()).userId()).isEqualTo("user-1");
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void noRequestThroughTheFilterWritesToTheStore() throws Exception {
        storedSessionIdleFor(Duration.ofSeconds(90));

        doFilter("GET", "/session/status");
        doFilter("POST", "/session/heartbeat");
        doFilter("GET", "/api/documents");

        verify(store, times(3)).findBySessionId("sid");
        verifyNoMoreInteractions(store);
    }

    @Test
    void idleExpiredSessionStaysAnonymousAndTheCookieIsCleared() throws Exception {
        storedSessionIdleFor(Duration.ofMinutes(3));

        MockHttpServletResponse response = doFilter("GET", "/session/status");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void requestWithoutCookieNeverTouchesTheStore() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/documents"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(store);
    }
}
