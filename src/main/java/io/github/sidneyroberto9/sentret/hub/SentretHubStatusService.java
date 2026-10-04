package io.github.sidneyroberto9.sentret.hub;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionConfigResponse;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionStatusResponse;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * Builds the status body served by the hub. Remaining times come from the principal the
 * authentication filter already loaded, so no endpoint reads the session row twice.
 */
@RequiredArgsConstructor
public class SentretHubStatusService {

    private final SentretProperties properties;

    public SessionStatusResponse status(SentretUser user) {
        SessionConfigResponse config = config();

        if (user == null) {
            return new SessionStatusResponse(false, null, null, null, null, config);
        }

        Instant now = Instant.now();

        return new SessionStatusResponse(
                true,
                user.userId(),
                user.email(),
                remainingMs(now, user.expiresAt()),
                idleRemainingMs(user, now),
                config);
    }

    private Long idleRemainingMs(SentretUser user, Instant now) {
        if (!properties.isIdleEnabled()) {
            return null;
        }

        return remainingMs(now, user.lastAccessedAt().plus(properties.getMaxIdle()));
    }

    private static long remainingMs(Instant now, Instant deadline) {
        return Math.max(0, Duration.between(now, deadline).toMillis());
    }

    private SessionConfigResponse config() {
        SentretProperties.Hub hub = properties.getHub();

        return new SessionConfigResponse(
                hub.getHeartbeatInterval().toMillis(),
                hub.getStatusPollInterval().toMillis(),
                hub.getWarningBefore().toMillis(),
                hub.getLoginUrl());
    }
}
