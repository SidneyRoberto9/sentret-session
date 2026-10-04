package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.service.SentretIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SentretIpResolverTest {

    @Test
    void usesRemoteAddrWhenForwardedNotTrusted() {
        SentretProperties props = new SentretProperties();
        props.setTrustForwardedFor(false);
        SentretIpResolver resolver = new SentretIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void picksClientIpBeforeTrustedProxyNotSpoofableLeftmost() {
        // Attacker injects 9.9.9.9; one trusted proxy appended 203.0.113.7 on the right.
        // With trustedProxyCount=1 the resolver must return 203.0.113.7, never the left-most spoof.
        SentretProperties props = new SentretProperties();
        props.setTrustForwardedFor(true);
        props.setTrustedProxyCount(1);
        SentretIpResolver resolver = new SentretIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9, 203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void handlesSingleForwardedEntry() {
        SentretProperties props = new SentretProperties();
        props.setTrustForwardedFor(true);
        props.setTrustedProxyCount(1);
        SentretIpResolver resolver = new SentretIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.4");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void fallsBackToRemoteAddrWhenForwardedHeaderMissing() {
        SentretProperties props = new SentretProperties();
        props.setTrustForwardedFor(true);
        SentretIpResolver resolver = new SentretIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void fallsBackToRemoteAddrWhenForwardedHeaderBlank() {
        SentretProperties props = new SentretProperties();
        props.setTrustForwardedFor(true);
        SentretIpResolver resolver = new SentretIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("10.0.0.10");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.10");
    }

    @Test
    void clampsIndexToZeroWhenTrustedProxyCountExceedsEntryCount() {
        // Only one hop present but configured to trust 5 proxies: index would go negative,
        // so the resolver clamps to 0 and returns the sole (left-most) entry.
        SentretProperties props = new SentretProperties();
        props.setTrustForwardedFor(true);
        props.setTrustedProxyCount(5);
        SentretIpResolver resolver = new SentretIpResolver(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");

        assertThat(resolver.resolve(request)).isEqualTo("1.2.3.4");
    }
}
