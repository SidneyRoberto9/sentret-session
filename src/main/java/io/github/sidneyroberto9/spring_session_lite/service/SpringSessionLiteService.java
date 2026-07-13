package io.github.sidneyroberto9.spring_session_lite.service;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.event.SpringSessionLiteSessionCreatedEvent;
import io.github.sidneyroberto9.spring_session_lite.event.SpringSessionLiteSessionDestroyedEvent;
import io.github.sidneyroberto9.spring_session_lite.event.SpringSessionLiteSessionRenewedEvent;
import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.store.SpringSessionLiteSessionStore;
import io.github.sidneyroberto9.spring_session_lite.util.NanoId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class SpringSessionLiteService {
    private final SpringSessionLiteIpHasher ipHasher;
    private final SpringSessionLiteSessionStore store;
    private final SpringSessionLiteProperties properties;
    private final SpringSessionLiteIpResolver ipResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final SpringSessionLiteCookieManager cookieManager;

    @Transactional
    public SpringSessionLiteUser login(String userId, String email, HttpServletRequest request, HttpServletResponse response) {
        return this.login(userId, email, List.of(), request, response);
    }

    @Transactional
    public SpringSessionLiteUser login(String userId, String email, List<String> roles, HttpServletRequest request, HttpServletResponse response) {
        Instant now = Instant.now();

        SpringSessionLiteSession session = new SpringSessionLiteSession();
        session.setSessionId(NanoId.generate(properties.getSessionIdLength()));
        session.setUserId(userId);
        session.setEmail(email);
        session.setRoles(joinRoles(roles));
        session.setIpHash(ipHasher.hash(ipResolver.resolve(request)));
        session.setCreatedAt(now);
        session.setLastAccessedAt(now);
        session.setExpiresAt(now.plus(properties.getTtl()));

        store.save(session);
        cookieManager.write(response, session.getSessionId());
        eventPublisher.publishEvent(new SpringSessionLiteSessionCreatedEvent(userId, session.getSessionId(), now));

        return toUser(session);
    }

    @Transactional
    public Optional<SpringSessionLiteUser> validate(String sessionId, HttpServletRequest request) {
        return store.findBySessionId(sessionId).flatMap(session -> {
            Instant now = Instant.now();

            if (session.getExpiresAt().isBefore(now)) {
                return Optional.empty();
            }

            if (isIdleEnabled()) {
                Instant reference = session.getLastAccessedAt() != null ? session.getLastAccessedAt() : session.getCreatedAt();

                if (reference.plus(properties.getMaxIdle()).isBefore(now)) {
                    return Optional.empty();
                }
            }

            String currentIpHash = ipHasher.hash(ipResolver.resolve(request));

            if (!session.getIpHash().equals(currentIpHash)) {
                return Optional.empty();
            }

            this.touch(session, now);

            return Optional.of(this.toUser(session));
        });
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = cookieManager.read(request);

        if (sessionId != null) {
            store.deleteBySessionId(sessionId);
            eventPublisher.publishEvent(new SpringSessionLiteSessionDestroyedEvent(sessionId));
        }

        cookieManager.clear(response);
    }

    @Transactional
    public void logout(String sessionId) {
        store.deleteBySessionId(sessionId);
        eventPublisher.publishEvent(new SpringSessionLiteSessionDestroyedEvent(sessionId));
    }

    @Transactional
    public void logoutAll(String userId) {
        store.deleteByUserId(userId);
    }

    @Transactional
    public void deleteExpired() {
        store.deleteExpired(Instant.now());
    }

    @Transactional
    public Optional<SpringSessionLiteUser> renew(String sessionId) {
        return store.findBySessionId(sessionId).map(session -> {
            Instant now = Instant.now();

            session.setExpiresAt(now.plus(properties.getTtl()));
            session.setLastAccessedAt(now);
            store.save(session);

            eventPublisher.publishEvent(new SpringSessionLiteSessionRenewedEvent(session.getUserId(), sessionId, now));

            return toUser(session);
        });
    }

    @Transactional
    public Optional<SpringSessionLiteUser> renew(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = cookieManager.read(request);

        if (sessionId == null) {
            return Optional.empty();
        }

        Optional<SpringSessionLiteUser> renewed = renew(sessionId);

        renewed.ifPresent(user -> cookieManager.write(response, sessionId));

        return renewed;
    }

    private void touch(SpringSessionLiteSession session, Instant now) {
        boolean idleEnabled = isIdleEnabled();

        if (!properties.isUpdateLastAccessed() && !properties.isSlidingExpiration() && !idleEnabled) {
            return;
        }

        Instant last = session.getLastAccessedAt();
        Duration throttle = effectiveLastAccessedThrottle();

        boolean withinThrottle = last != null && last.plus(throttle).isAfter(now);

        if (withinThrottle) {
            return;
        }

        if (properties.isUpdateLastAccessed() || idleEnabled) {
            session.setLastAccessedAt(now);
        }

        if (properties.isSlidingExpiration()) {
            session.setExpiresAt(now.plus(properties.getTtl()));
        }

        store.save(session);
    }

    private boolean isIdleEnabled() {
        Duration maxIdle = properties.getMaxIdle();
        return maxIdle != null && !maxIdle.isZero() && !maxIdle.isNegative();
    }

    private Duration effectiveLastAccessedThrottle() {
        Duration configured = properties.getLastAccessedThrottle();

        if (!isIdleEnabled()) {
            return configured;
        }

        Duration halfMaxIdle = properties.getMaxIdle().dividedBy(2);

        return configured.compareTo(halfMaxIdle) <= 0 ? configured : halfMaxIdle;
    }

    private SpringSessionLiteUser toUser(SpringSessionLiteSession session) {
        return new SpringSessionLiteUser(session.getUserId(), session.getEmail(), session.getSessionId(), splitRoles(session.getRoles()));
    }

    static String joinRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }

        return String.join(",", roles);
    }

    static List<String> splitRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return List.of();
        }

        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .toList();
    }
}
