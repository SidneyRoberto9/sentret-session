package io.github.sidneyroberto9.sentret.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;

/**
 * Warns at startup about configurations that start fine but lose the cookie, log active users out
 * or weaken CSRF.
 */
@Slf4j
@RequiredArgsConstructor
public class SentretSecurityValidator implements InitializingBean {

    private final SentretProperties properties;

    @Override
    public void afterPropertiesSet() {
        SentretProperties.Hub hub = properties.getHub();
        Duration maxIdle = properties.getMaxIdle();

        if ("None".equalsIgnoreCase(properties.getCookieSameSite()) && !properties.isCsrfEnabled()) {
            log.warn("[sentret] 'cookie-same-site=None' with CSRF disabled is unsafe for cookie-based auth. "
                    + "Enable 'sentret.csrf-enabled' or use SameSite=Lax/Strict.");
        }

        if ("None".equalsIgnoreCase(properties.getCookieSameSite()) && !properties.isCookieSecure()) {
            log.warn("[sentret] 'cookie-same-site=None' requires 'cookie-secure=true': browsers drop a SameSite=None "
                    + "cookie that is not Secure, so logins succeed but no session reaches the next request.");
        }

        if (properties.isIdleEnabled() && hub.getHeartbeatInterval().compareTo(maxIdle.dividedBy(2)) >= 0) {
            log.warn("[sentret] 'heartbeat-interval' ({}) is >= half of 'max-idle' ({}). The heartbeat is the only "
                    + "thing that resets the idle window, so an active user can be logged out anyway. Set "
                    + "'sentret.hub.heartbeat-interval' to a quarter of 'max-idle' or less.", hub.getHeartbeatInterval(), maxIdle);
        }

        if (hub.getStatusPollInterval().compareTo(hub.getWarningBefore()) >= 0) {
            log.warn("[sentret] 'status-poll-interval' ({}) is >= 'warning-before' ({}). A session can go from outside "
                    + "the warning window straight to expired without the user ever seeing the warning. Set "
                    + "'sentret.hub.status-poll-interval' well below 'warning-before'.", hub.getStatusPollInterval(), hub.getWarningBefore());
        }

        if (properties.isIdleEnabled() && hub.getWarningBefore().compareTo(maxIdle) >= 0) {
            log.warn("[sentret] 'warning-before' ({}) is >= 'max-idle' ({}), so the client shows the inactivity "
                    + "warning as soon as the session starts. Set 'sentret.hub.warning-before' below 'max-idle'.",
                    hub.getWarningBefore(), maxIdle);
        }
    }
}
