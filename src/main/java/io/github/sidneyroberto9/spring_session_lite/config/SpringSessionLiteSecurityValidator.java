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

        if (idleEnabled && properties.getLastAccessedThrottle().compareTo(maxIdle.dividedBy(2)) >= 0) {
            log.warn("[spring-session-lite] 'last-accessed-throttle' ({}) is >= half of 'max-idle' ({}). " + "The library caps the effective throttle at max-idle/2 internally so idle detection stays " + "accurate, but consider lowering 'spring-session-lite.last-accessed-throttle' explicitly.", properties.getLastAccessedThrottle(), maxIdle);
        }
    }
}
