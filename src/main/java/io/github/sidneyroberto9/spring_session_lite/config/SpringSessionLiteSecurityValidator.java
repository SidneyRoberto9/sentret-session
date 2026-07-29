package io.github.sidneyroberto9.spring_session_lite.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class SpringSessionLiteSecurityValidator implements InitializingBean {

    private final SpringSessionLiteProperties properties;

    @Override
    public void afterPropertiesSet() {
        if (properties.isCookieSecure() && SpringSessionLiteProperties.DEFAULT_IP_HASH_SALT.equals(properties.getIpHashSalt())) {
            log.warn("[spring-session-lite] 'ip-hash-salt' is still the default in a secure setup. " + "Set a strong 'spring-session-lite.ip-hash-salt' (e.g. via env var) in production.");
        }

        if ("None".equalsIgnoreCase(properties.getCookieSameSite()) && !properties.isCsrfEnabled()) {
            log.warn("[spring-session-lite] 'cookie-same-site=None' with CSRF disabled is unsafe for " + "cookie-based auth. Enable 'spring-session-lite.csrf-enabled' or use SameSite=Lax/Strict.");
        }

        Duration maxIdle = properties.getMaxIdle();
        boolean idleEnabled = maxIdle != null && !maxIdle.isZero() && !maxIdle.isNegative();

        if (idleEnabled && properties.getHeartbeatInterval().compareTo(maxIdle.dividedBy(2)) >= 0) {
            log.warn("[spring-session-lite] 'heartbeat-interval' ({}) is >= half of 'max-idle' ({}). " + "The heartbeat is the only thing that resets the idle window, and the client throttles it to " + "this interval — so an active user can be logged out anyway, and the warning can show while " + "they are still working. Set 'spring-session-lite.heartbeat-interval' well below 'max-idle' " + "(a quarter of it or less).", properties.getHeartbeatInterval(), maxIdle);
        }

        Duration idleWatchInterval = properties.getIdleWatchInterval();

        if (properties.isSseEnabled() && idleWatchInterval != null && idleWatchInterval.compareTo(properties.getWarningBefore()) >= 0) {
            log.warn("[spring-session-lite] 'idle-watch-interval' ({}) is >= 'warning-before' ({}). The sweep is the " + "only thing that pushes the inactivity warning, so at this cadence a session can go from " + "outside the warning window straight to expired without the client ever being warned. Set " + "'spring-session-lite.idle-watch-interval' well below 'warning-before'.", idleWatchInterval, properties.getWarningBefore());
        }

        if (idleEnabled && properties.getWarningBefore().compareTo(maxIdle) >= 0) {
            log.warn("[spring-session-lite] 'warning-before' ({}) is >= 'max-idle' ({}), so the inactivity " + "warning is inside its window from the moment the session starts and the client shows it " + "immediately. Set 'spring-session-lite.warning-before' below 'max-idle'.", properties.getWarningBefore(), maxIdle);
        }
    }
}
