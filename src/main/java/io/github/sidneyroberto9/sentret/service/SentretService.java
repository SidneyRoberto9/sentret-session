package io.github.sidneyroberto9.sentret.service;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.event.SentretSessionCreatedEvent;
import io.github.sidneyroberto9.sentret.event.SentretSessionDestroyedEvent;
import io.github.sidneyroberto9.sentret.event.SentretSessionRenewedEvent;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.store.SentretSession;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class SentretService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder SESSION_ID_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** 15 bytes = 120 random bits = exactly 20 Base64 URL characters, no padding. */
    private static final int SESSION_ID_BYTES = 15;

    /** Shape of every id {@link #newSessionId()} produces; anything else is rejected without a query. */
    private static final Pattern SESSION_ID_FORMAT = Pattern.compile("[A-Za-z0-9_-]{20}");

    private final SentretSessionStore store;
    private final SentretProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final SentretCookieManager cookieManager;

    public SentretUser login(String userId, String email, HttpServletResponse response) {
        Instant now = Instant.now();
        purgeExpired(now);
        SentretSession session = new SentretSession(newSessionId(), userId, email, now, now.plus(properties.getTtl()), now);

        store.insert(session);
        cookieManager.write(response, session.sessionId());
        eventPublisher.publishEvent(new SentretSessionCreatedEvent(userId, session.sessionId(), now));

        return toUser(session);
    }

    /**
     * Who the session belongs to; empty when it is unknown, past its absolute expiry, or idle for
     * longer than max-idle. Read-only on purpose: validating is not activity (see {@link #touch}).
     * The id must match exactly, even on databases whose default collation ignores case.
     */
    public Optional<SentretUser> validate(String sessionId) {
        Instant now = Instant.now();

        return find(sessionId)
                .filter(session -> !session.expiresAt().isBefore(now))
                .filter(session -> !isIdleExpired(session.lastAccessedAt(), now))
                .map(this::toUser);
    }

    /**
     * Records real user activity (the hub heartbeat). One UPDATE, no re-read, no throttle: the
     * client already throttles heartbeats to heartbeat-interval, and dropping one here would discard
     * the only activity signal there is.
     */
    public SentretUser touch(SentretUser user) {
        if (!properties.isIdleEnabled()) {
            return user;
        }

        Instant now = Instant.now();
        store.updateLastAccessedAt(user.sessionId(), now);

        return new SentretUser(user.userId(), user.email(), user.sessionId(), user.expiresAt(), now);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = cookieManager.read(request);

        if (sessionId != null) {
            logout(sessionId);
        }

        cookieManager.clear(response);
    }

    /**
     * Destroys the session and publishes {@link SentretSessionDestroyedEvent} with its userId. A
     * no-op, no-event call when the session is already gone.
     */
    public void logout(String sessionId) {
        find(sessionId).ifPresent(session -> {
            store.deleteBySessionId(sessionId);
            eventPublisher.publishEvent(new SentretSessionDestroyedEvent(session.userId(), sessionId));
        });
    }

    public void logoutAll(String userId) {
        store.deleteByUserId(userId);
    }

    /** Resets both deadlines of a still-valid session. Never resurrects an expired one. */
    public Optional<SentretUser> renew(String sessionId) {
        return validate(sessionId).map(this::extend);
    }

    /**
     * Renews the session of a principal the filter already validated (the hub renew), without
     * reading the row again, and rewrites the cookie.
     */
    public SentretUser renew(SentretUser user, HttpServletResponse response) {
        SentretUser renewed = extend(user);
        cookieManager.write(response, renewed.sessionId());
        return renewed;
    }

    /**
     * The row for exactly this id: malformed ids never reach the store, and a row whose id differs
     * only in case (case-folding collations) is not a match.
     */
    private Optional<SentretSession> find(String sessionId) {
        if (!SESSION_ID_FORMAT.matcher(sessionId).matches()) {
            return Optional.empty();
        }

        return store.findBySessionId(sessionId).filter(session -> session.sessionId().equals(sessionId));
    }

    private SentretUser extend(SentretUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getTtl());

        store.updateExpiresAt(user.sessionId(), expiresAt, now);
        eventPublisher.publishEvent(new SentretSessionRenewedEvent(user.userId(), user.sessionId(), now));

        return new SentretUser(user.userId(), user.email(), user.sessionId(), expiresAt, now);
    }

    private boolean isIdleExpired(Instant lastAccessedAt, Instant now) {
        return properties.isIdleEnabled() && lastAccessedAt.plus(properties.getMaxIdle()).isBefore(now);
    }

    /**
     * Housekeeping only: a failure here (lock timeout, deadlock under concurrent logins) is logged
     * and the login goes on. Expired rows are rejected by {@link #validate} anyway.
     */
    private void purgeExpired(Instant now) {
        try {
            store.deleteExpired(now);
        } catch (RuntimeException e) {
            log.warn("[sentret] Could not purge expired sessions; the login proceeds.", e);
        }
    }

    private static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return SESSION_ID_ENCODER.encodeToString(bytes);
    }

    private SentretUser toUser(SentretSession session) {
        return new SentretUser(session.userId(), session.email(), session.sessionId(), session.expiresAt(), session.lastAccessedAt());
    }
}
