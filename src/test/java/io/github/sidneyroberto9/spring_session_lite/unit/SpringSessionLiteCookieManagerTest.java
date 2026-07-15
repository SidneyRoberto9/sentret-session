package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteCookieManager;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringSessionLiteCookieManagerTest {

    private SpringSessionLiteProperties properties() {
        return new SpringSessionLiteProperties();
    }

    @Test
    void cookieNameUsesDefaultPrefix() {
        SpringSessionLiteCookieManager manager = new SpringSessionLiteCookieManager(properties());

        assertThat(manager.cookieName()).isEqualTo("SLSID");
    }

    @Test
    void cookieNameAppliesCustomPrefix() {
        SpringSessionLiteProperties properties = properties();
        properties.setCookiePrefix("__Host-");
        SpringSessionLiteCookieManager manager = new SpringSessionLiteCookieManager(properties);

        assertThat(manager.cookieName()).isEqualTo("__Host-SLSID");
    }

    @Test
    void readReturnsNullWhenNoCookiesPresent() {
        SpringSessionLiteCookieManager manager = new SpringSessionLiteCookieManager(properties());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);

        assertThat(manager.read(request)).isNull();
    }

    @Test
    void readReturnsNullWhenCookieNotFound() {
        SpringSessionLiteCookieManager manager = new SpringSessionLiteCookieManager(properties());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("other", "value")});

        assertThat(manager.read(request)).isNull();
    }

    @Test
    void readReturnsMatchingCookieValue() {
        SpringSessionLiteCookieManager manager = new SpringSessionLiteCookieManager(properties());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie("other", "value"),
                new Cookie("SLSID", "abc123")
        });

        assertThat(manager.read(request)).isEqualTo("abc123");
    }

    @Test
    void writeAddsSetCookieHeaderWithDefaults() {
        SpringSessionLiteCookieManager manager = new SpringSessionLiteCookieManager(properties());
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.write(response, "session-123");

        String header = response.getHeader("Set-Cookie");
        assertThat(header).contains("SLSID=session-123");
        assertThat(header).contains("Max-Age=28800");
        assertThat(header).contains("Secure");
        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("SameSite=Lax");
        assertThat(header).contains("Path=/");
        assertThat(header).doesNotContain("Domain");
    }

    @Test
    void writeAppliesCustomDomainSecureFalseAndSameSite() {
        SpringSessionLiteProperties properties = properties();
        properties.setCookieDomain("example.com");
        properties.setCookieSecure(false);
        properties.setCookieSameSite("Strict");
        SpringSessionLiteCookieManager manager = new SpringSessionLiteCookieManager(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.write(response, "session-456");

        String header = response.getHeader("Set-Cookie");
        assertThat(header).contains("Domain=example.com");
        assertThat(header).contains("SameSite=Strict");
        assertThat(header).doesNotContain("Secure");
    }

    @Test
    void clearAddsExpiredCookieHeader() {
        SpringSessionLiteCookieManager manager = new SpringSessionLiteCookieManager(properties());
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.clear(response);

        String header = response.getHeader("Set-Cookie");
        assertThat(header).startsWith("SLSID=");
        assertThat(header).contains("Max-Age=0");
    }
}
