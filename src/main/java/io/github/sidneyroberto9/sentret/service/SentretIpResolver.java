package io.github.sidneyroberto9.sentret.service;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SentretIpResolver {

    private final SentretProperties properties;

    public String resolve(HttpServletRequest request) {
        if (properties.isTrustForwardedFor()) {

            String xff = request.getHeader("X-Forwarded-For");

            if (xff != null && !xff.isBlank()) {
                return pickClientIp(xff);
            }
        }

        return request.getRemoteAddr();
    }

    /**
     * The left-most X-Forwarded-For entry is client-controlled and spoofable. With N trusted
     * proxies appending to the header, the real client IP sits at position (size - N) from the
     * left — i.e. the first hop before the trusted proxy chain. We pick that one, never index 0.
     */
    private String pickClientIp(String xff) {
        String[] parts = xff.split(",");

        int trusted = Math.max(1, properties.getTrustedProxyCount());
        int index = parts.length - trusted;

        if (index < 0) {
            index = 0;
        }

        return parts[index].trim();
    }
}
