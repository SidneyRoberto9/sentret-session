package io.github.sidneyroberto9.spring_session_lite.unit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteSecurityValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringSessionLiteSecurityValidatorTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(SpringSessionLiteSecurityValidator.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private void validate(SpringSessionLiteProperties properties) {
        new SpringSessionLiteSecurityValidator(properties).afterPropertiesSet();
    }

    private List<ILoggingEvent> heartbeatWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("'heartbeat-interval'"))
                .toList();
    }

    private List<ILoggingEvent> warningBeforeWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("'warning-before'"))
                .toList();
    }

    private List<ILoggingEvent> idleWatchIntervalWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("'idle-watch-interval'"))
                .toList();
    }

    private List<ILoggingEvent> sameSiteWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("'cookie-same-site=None'"))
                .toList();
    }

    private List<ILoggingEvent> saltWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("'ip-hash-salt'"))
                .toList();
    }

    /**
     * The heartbeat is the only thing that resets the idle window, and the client throttles it to
     * `heartbeat-interval`. Too close to `max-idle` and an active user gets logged out anyway.
     */
    @Test
    void warnsWhenHeartbeatIntervalEqualsHalfMaxIdle() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(2));
        properties.setHeartbeatInterval(Duration.ofMinutes(1)); // == maxIdle / 2

        validate(properties);

        assertThat(heartbeatWarnings()).hasSize(1);
    }

    @Test
    void warnsWhenHeartbeatIntervalExceedsHalfMaxIdle() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(1));
        properties.setHeartbeatInterval(Duration.ofSeconds(60)); // == maxIdle: cannot ever rescue

        validate(properties);

        assertThat(heartbeatWarnings()).hasSize(1);
    }

    @Test
    void doesNotWarnWhenHeartbeatIntervalIsWellBelowMaxIdle() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.setHeartbeatInterval(Duration.ofMinutes(1));

        validate(properties);

        assertThat(heartbeatWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnWhenMaxIdleDisabled() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties(); // maxIdle = ZERO
        properties.setHeartbeatInterval(Duration.ofMinutes(30));

        validate(properties);

        assertThat(heartbeatWarnings()).isEmpty();
    }

    /**
     * `warning-before >= max-idle` makes the warning window cover the whole session, so the card
     * shows from the moment the user logs in.
     */
    @Test
    void warnsWhenWarningBeforeCoversWholeIdleWindow() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(1));
        properties.setWarningBefore(Duration.ofSeconds(60)); // == maxIdle

        validate(properties);

        assertThat(warningBeforeWarnings()).hasSize(1);
    }

    @Test
    void doesNotWarnWhenWarningBeforeIsBelowMaxIdle() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(2));
        properties.setWarningBefore(Duration.ofSeconds(30));

        validate(properties);

        assertThat(warningBeforeWarnings()).isEmpty();
    }

    /**
     * The sweep is the only thing that pushes the inactivity warning. At a cadence no shorter than
     * `warning-before`, a session can go from outside the warning window to expired between two
     * ticks, so the client is logged out without ever having been warned.
     */
    @Test
    void warnsWhenIdleWatchIntervalIsNotBelowWarningBefore() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setSseEnabled(true);
        properties.setWarningBefore(Duration.ofSeconds(60));
        properties.setIdleWatchInterval(Duration.ofSeconds(60));

        validate(properties);

        assertThat(idleWatchIntervalWarnings()).hasSize(1);
    }

    @Test
    void doesNotWarnWhenIdleWatchIntervalIsWellBelowWarningBefore() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setSseEnabled(true);
        properties.setWarningBefore(Duration.ofSeconds(60));
        properties.setIdleWatchInterval(Duration.ofSeconds(10));

        validate(properties);

        assertThat(idleWatchIntervalWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnAboutIdleWatchIntervalWhenSseIsDisabled() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setWarningBefore(Duration.ofSeconds(10));
        properties.setIdleWatchInterval(Duration.ofSeconds(60));

        validate(properties);

        assertThat(idleWatchIntervalWarnings()).isEmpty();
    }

    /**
     * An empty {@code idle-watch-interval=} binds to {@code null}; that is rejected outright by the
     * SSE autoconfiguration, so this validator must not NPE on the way there.
     */
    @Test
    void doesNotWarnAboutIdleWatchIntervalWhenItIsNull() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setSseEnabled(true);
        properties.setIdleWatchInterval(null);

        validate(properties);

        assertThat(idleWatchIntervalWarnings()).isEmpty();
    }

    /**
     * `SameSite=None` requires the browser to send the cookie cross-site; without CSRF protection
     * that is an open door for cross-site request forgery.
     */
    @Test
    void warnsWhenSameSiteNoneWithCsrfDisabled() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setCookieSameSite("None");
        properties.setCsrfEnabled(false);

        validate(properties);

        assertThat(sameSiteWarnings()).hasSize(1);
    }

    @Test
    void doesNotWarnWhenSameSiteNoneWithCsrfEnabled() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setCookieSameSite("None");
        properties.setCsrfEnabled(true);

        validate(properties);

        assertThat(sameSiteWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnAboutSaltWhenCookieIsNotSecure() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setCookieSecure(false);

        validate(properties);

        assertThat(saltWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnAboutSaltWhenCookieSecureWithCustomSalt() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setCookieSecure(true);
        properties.setIpHashSalt("a-strong-custom-salt");

        validate(properties);

        assertThat(saltWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnAboutHeartbeatOrWarningBeforeWhenMaxIdleIsNull() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(null);

        validate(properties);

        assertThat(heartbeatWarnings()).isEmpty();
        assertThat(warningBeforeWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnAboutHeartbeatOrWarningBeforeWhenMaxIdleIsNegative() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(-1));

        validate(properties);

        assertThat(heartbeatWarnings()).isEmpty();
        assertThat(warningBeforeWarnings()).isEmpty();
    }
}
