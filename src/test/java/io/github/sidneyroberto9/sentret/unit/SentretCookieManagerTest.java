package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SentretCookieManagerTest {

    private SentretProperties properties() {
        return new SentretProperties();
    }

    @Test
    void cookieNameDefaultsToSentretSid() {
        SentretCookieManager manager = new SentretCookieManager(properties());

        assertThat(manager.cookieName()).isEqualTo("SENTRETSID");
    }

    @Test
    void cookieNameCanCarryTheHostPrefix() {
        SentretProperties properties = properties();
        properties.setCookieName("__Host-SID");
        SentretCookieManager manager = new SentretCookieManager(properties);

        assertThat(manager.cookieName()).isEqualTo("__Host-SID");
    }

    @Test
    void readReturnsNullWhenNoCookiesPresent() {
        SentretCookieManager manager = new SentretCookieManager(properties());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);

        assertThat(manager.read(request)).isNull();
    }

    @Test
    void readReturnsNullWhenCookieNotFound() {
        SentretCookieManager manager = new SentretCookieManager(properties());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("other", "value")});

        assertThat(manager.read(request)).isNull();
    }

    @Test
    void readReturnsMatchingCookieValue() {
        SentretCookieManager manager = new SentretCookieManager(properties());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie("other", "value"),
                new Cookie("SENTRETSID", "abc123")
        });

        assertThat(manager.read(request)).isEqualTo("abc123");
    }

    @Test
    void writeAddsSetCookieHeaderWithDefaults() {
        SentretCookieManager manager = new SentretCookieManager(properties());
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.write(response, "session-123");

        String header = response.getHeader("Set-Cookie");
        assertThat(header).contains("SENTRETSID=session-123");
        assertThat(header).contains("Max-Age=28800");
        assertThat(header).contains("Secure");
        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("SameSite=Lax");
        assertThat(header).contains("Path=/");
        assertThat(header).doesNotContain("Domain");
    }

    @Test
    void writeAppliesCustomDomainSecureFalseAndSameSite() {
        SentretProperties properties = properties();
        properties.setCookieDomain("example.com");
        properties.setCookieSecure(false);
        properties.setCookieSameSite("Strict");
        SentretCookieManager manager = new SentretCookieManager(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.write(response, "session-456");

        String header = response.getHeader("Set-Cookie");
        assertThat(header).contains("Domain=example.com");
        assertThat(header).contains("SameSite=Strict");
        assertThat(header).doesNotContain("Secure");
    }

    @Test
    void clearAddsExpiredCookieHeader() {
        SentretCookieManager manager = new SentretCookieManager(properties());
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.clear(response);

        String header = response.getHeader("Set-Cookie");
        assertThat(header).startsWith("SENTRETSID=");
        assertThat(header).contains("Max-Age=0");
    }
}
