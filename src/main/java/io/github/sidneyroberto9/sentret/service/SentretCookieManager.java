package io.github.sidneyroberto9.sentret.service;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

@RequiredArgsConstructor
public class SentretCookieManager {

    private final SentretProperties properties;

    public String cookieName() {
        return properties.getCookieName();
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        String name = cookieName();

        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    public void write(HttpServletResponse response, String sessionId) {
        response.addHeader("Set-Cookie", build(sessionId, properties.getTtl()).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookieName(), value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path("/")
                .maxAge(maxAge);

        if (properties.getCookieDomain() != null) {
            builder.domain(properties.getCookieDomain());
        }

        return builder.build();
    }
}
