package io.github.sidneyroberto9.sentret.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class SentretSecurityValidator implements InitializingBean {

    private final SentretProperties properties;

    @Override
    public void afterPropertiesSet() {
        if (properties.isCookieSecure() && SentretProperties.DEFAULT_IP_HASH_SALT.equals(properties.getIpHashSalt())) {
            log.warn("[sentret] 'ip-hash-salt' is still the default in a secure setup. " + "Set a strong 'sentret.ip-hash-salt' (e.g. via env var) in production.");
        }

        if ("None".equalsIgnoreCase(properties.getCookieSameSite()) && !properties.isCsrfEnabled()) {
            log.warn("[sentret] 'cookie-same-site=None' with CSRF disabled is unsafe for " + "cookie-based auth. Enable 'sentret.csrf-enabled' or use SameSite=Lax/Strict.");
        }

        Duration maxIdle = properties.getMaxIdle();
        boolean idleEnabled = maxIdle != null && !maxIdle.isZero() && !maxIdle.isNegative();

        if (idleEnabled && properties.getHeartbeatInterval().compareTo(maxIdle.dividedBy(2)) >= 0) {
            log.warn("[sentret] 'heartbeat-interval' ({}) is >= half of 'max-idle' ({}). " + "The heartbeat is the only thing that resets the idle window, and the client throttles it to " + "this interval — so an active user can be logged out anyway, and the warning can show while " + "they are still working. Set 'sentret.heartbeat-interval' well below 'max-idle' " + "(a quarter of it or less).", properties.getHeartbeatInterval(), maxIdle);
        }

        if (properties.getStatusPollInterval().compareTo(properties.getWarningBefore()) >= 0) {
            log.warn("[sentret] 'status-poll-interval' ({}) is >= 'warning-before' ({}). The client's " + "status poll is the only thing that raises the inactivity warning, so at this cadence a " + "session can go from outside the warning window straight to expired without the user ever " + "seeing the card. Set 'sentret.status-poll-interval' well below 'warning-before'.", properties.getStatusPollInterval(), properties.getWarningBefore());
        }

        if (idleEnabled && properties.getWarningBefore().compareTo(maxIdle) >= 0) {
            log.warn("[sentret] 'warning-before' ({}) is >= 'max-idle' ({}), so the inactivity " + "warning is inside its window from the moment the session starts and the client shows it " + "immediately. Set 'sentret.warning-before' below 'max-idle'.", properties.getWarningBefore(), maxIdle);
        }
    }
}
