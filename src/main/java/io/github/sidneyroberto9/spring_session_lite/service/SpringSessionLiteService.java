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

    /**
     * Validates the session and reports who it belongs to, without counting the request as user
     * activity.
     *
     * <p>Deliberately does <strong>not</strong> {@link #touch(String)}: the frontend client
     * observes the session in the background (it polls {@code GET /session/status} every
     * {@code statusPollInterval} and holds {@code GET /session/stream} open), and those requests
     * are indistinguishable from any other here. Touching on every authenticated request made the
     * idle clock unreachable — with the effective throttle capped at {@code maxIdle / 2}, a poll
     * that is more frequent than that refreshes {@code lastAccessedAt} forever and {@code maxIdle}
     * never elapses. Activity is now signalled explicitly, and only, by
     * {@code POST /session/heartbeat} (see {@link #touch(String)}), which the client fires from
     * real DOM events.
     */
    @Transactional(readOnly = true)
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

            return Optional.of(this.toUser(session));
        });
    }

    /**
     * Records real user activity against the session, resetting the idle window (and sliding the
     * absolute expiry when {@code sliding-expiration} is on). The sole activity signal in the
     * library — {@code POST /session/heartbeat} calls this; {@link #validate} intentionally does
     * not. Every call writes: the client already throttles heartbeats to {@code heartbeat-interval},
     * so write volume is bounded there rather than here — and throttling a heartbeat would discard
     * the only activity signal the library has. A no-op when {@code sessionId} is unknown.
     */
    @Transactional
    public void touch(String sessionId) {
        store.findBySessionId(sessionId).ifPresent(session -> this.touch(session, Instant.now()));
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = cookieManager.read(request);

        if (sessionId != null) {
            logout(sessionId);
        }

        cookieManager.clear(response);
    }

    /**
     * Destroys the session and publishes {@link SpringSessionLiteSessionDestroyedEvent}. Looks the
     * session up first (rather than deleting blind) so the event can carry {@code userId} — a
     * no-op, no-event call when {@code sessionId} is already gone (e.g. a race with a concurrent
     * logout), instead of publishing an event for a user nobody can identify.
     */
    @Transactional
    public void logout(String sessionId) {
        store.findBySessionId(sessionId).ifPresent(session -> {
            store.deleteBySessionId(sessionId);
            eventPublisher.publishEvent(new SpringSessionLiteSessionDestroyedEvent(session.getUserId(), sessionId));
        });
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

    /**
     * Read-only snapshot of remaining time for the given session, for status/heartbeat/renew
     * responses. Does not touch/validate the session — callers that need validation call
     * {@link #validate(String, HttpServletRequest)} first (the authentication filter already does
     * this on every authenticated request).
     */
    @Transactional(readOnly = true)
    public Optional<SpringSessionLiteSessionRemaining> remaining(String sessionId) {
        return store.findBySessionId(sessionId).map(session -> {
            Instant now = Instant.now();

            long absoluteRemainingMs = Math.max(0, Duration.between(now, session.getExpiresAt()).toMillis());

            Long idleRemainingMs = null;

            if (isIdleEnabled()) {
                Instant reference = session.getLastAccessedAt() != null ? session.getLastAccessedAt() : session.getCreatedAt();
                idleRemainingMs = Math.max(0, Duration.between(now, reference.plus(properties.getMaxIdle())).toMillis());
            }

            return new SpringSessionLiteSessionRemaining(absoluteRemainingMs, idleRemainingMs);
        });
    }

    /**
     * Records activity unconditionally — no throttle.
     *
     * <p>Until 2.1.1 this was throttled to {@code min(last-accessed-throttle, max-idle / 2)}, back
     * when every authenticated request landed here and the throttle was what kept it from being a
     * DB write per request. Now that only {@code POST /session/heartbeat} reaches it, throttling
     * here is pure loss: it silently discards the one activity signal the system has, so a user
     * moving the mouse inside the throttle window did not extend their session and got logged out
     * while active. Write amplification is already bounded on the client side, which throttles
     * heartbeats to {@code heartbeat-interval} — at most one write per session per interval.
     */
    private void touch(SpringSessionLiteSession session, Instant now) {
        boolean idleEnabled = isIdleEnabled();

        if (!properties.isUpdateLastAccessed() && !properties.isSlidingExpiration() && !idleEnabled) {
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
