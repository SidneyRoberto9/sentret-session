package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringSessionLiteIpResolverTest {

    @Test
    void usesRemoteAddrWhenForwardedNotTrusted() {
        SpringSessionLiteProperties props = new SpringSessionLiteProperties();
        props.setTrustForwardedFor(false);
        SpringSessionLiteIpResolver resolver = new SpringSessionLiteIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void picksClientIpBeforeTrustedProxyNotSpoofableLeftmost() {
        // Attacker injects 9.9.9.9; one trusted proxy appended 203.0.113.7 on the right.
        // With trustedProxyCount=1 the resolver must return 203.0.113.7, never the left-most spoof.
        SpringSessionLiteProperties props = new SpringSessionLiteProperties();
        props.setTrustForwardedFor(true);
        props.setTrustedProxyCount(1);
        SpringSessionLiteIpResolver resolver = new SpringSessionLiteIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9, 203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void handlesSingleForwardedEntry() {
        SpringSessionLiteProperties props = new SpringSessionLiteProperties();
        props.setTrustForwardedFor(true);
        props.setTrustedProxyCount(1);
        SpringSessionLiteIpResolver resolver = new SpringSessionLiteIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.4");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void fallsBackToRemoteAddrWhenForwardedHeaderMissing() {
        SpringSessionLiteProperties props = new SpringSessionLiteProperties();
        props.setTrustForwardedFor(true);
        SpringSessionLiteIpResolver resolver = new SpringSessionLiteIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void fallsBackToRemoteAddrWhenForwardedHeaderBlank() {
        SpringSessionLiteProperties props = new SpringSessionLiteProperties();
        props.setTrustForwardedFor(true);
        SpringSessionLiteIpResolver resolver = new SpringSessionLiteIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("10.0.0.10");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.10");
    }

    @Test
    void clampsIndexToZeroWhenTrustedProxyCountExceedsEntryCount() {
        // Only one hop present but configured to trust 5 proxies: index would go negative,
        // so the resolver clamps to 0 and returns the sole (left-most) entry.
        SpringSessionLiteProperties props = new SpringSessionLiteProperties();
        props.setTrustForwardedFor(true);
        props.setTrustedProxyCount(5);
        SpringSessionLiteIpResolver resolver = new SpringSessionLiteIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");

        assertThat(resolver.resolve(request)).isEqualTo("1.2.3.4");
    }
}
