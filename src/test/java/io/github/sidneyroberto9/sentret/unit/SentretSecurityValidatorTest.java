package io.github.sidneyroberto9.sentret.unit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.config.SentretSecurityValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentretSecurityValidatorTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(SentretSecurityValidator.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private void validate(SentretProperties properties) {
        new SentretSecurityValidator(properties).afterPropertiesSet();
    }

    private List<ILoggingEvent> heartbeatWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("'heartbeat-interval'"))
                .toList();
    }

    /**
     * Matches on the comparison, not on {@code 'warning-before'} alone: the status-poll warning
     * names that property too, so a looser filter counts it as a max-idle warning.
     */
    private List<ILoggingEvent> warningBeforeWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("is >= 'max-idle'"))
                .toList();
    }

    private List<ILoggingEvent> statusPollIntervalWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("'status-poll-interval'"))
                .toList();
    }

    private List<ILoggingEvent> sameSiteWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("'cookie-same-site=None'"))
                .toList();
    }

    /**
     * The heartbeat is the only thing that resets the idle window, and the client throttles it to
     * `heartbeat-interval`. Too close to `max-idle` and an active user gets logged out anyway.
     */
    @Test
    void warnsWhenHeartbeatIntervalEqualsHalfMaxIdle() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(2));
        properties.setHeartbeatInterval(Duration.ofMinutes(1)); // == maxIdle / 2

        validate(properties);

        assertThat(heartbeatWarnings()).hasSize(1);
    }

    @Test
    void warnsWhenHeartbeatIntervalExceedsHalfMaxIdle() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(1));
        properties.setHeartbeatInterval(Duration.ofSeconds(60)); // == maxIdle: cannot ever rescue

        validate(properties);

        assertThat(heartbeatWarnings()).hasSize(1);
    }

    @Test
    void doesNotWarnWhenHeartbeatIntervalIsWellBelowMaxIdle() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.setHeartbeatInterval(Duration.ofMinutes(1));

        validate(properties);

        assertThat(heartbeatWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnWhenMaxIdleDisabled() {
        SentretProperties properties = new SentretProperties(); // maxIdle = ZERO
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
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(1));
        properties.setWarningBefore(Duration.ofSeconds(60)); // == maxIdle

        validate(properties);

        assertThat(warningBeforeWarnings()).hasSize(1);
    }

    @Test
    void doesNotWarnWhenWarningBeforeIsBelowMaxIdle() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(2));
        properties.setWarningBefore(Duration.ofSeconds(30));

        validate(properties);

        assertThat(warningBeforeWarnings()).isEmpty();
    }

    /**
     * The client's status poll is the only thing that raises the inactivity warning. At a cadence no
     * shorter than `warning-before`, a session can go from outside the warning window to expired
     * between two polls, so the user is logged out without ever having seen the card.
     */
    @Test
    void warnsWhenStatusPollIntervalIsNotBelowWarningBefore() {
        SentretProperties properties = new SentretProperties();
        properties.setWarningBefore(Duration.ofSeconds(60));
        properties.setStatusPollInterval(Duration.ofSeconds(60));

        validate(properties);

        assertThat(statusPollIntervalWarnings()).hasSize(1);
    }

    @Test
    void doesNotWarnWhenStatusPollIntervalIsWellBelowWarningBefore() {
        SentretProperties properties = new SentretProperties();
        properties.setWarningBefore(Duration.ofSeconds(120));
        properties.setStatusPollInterval(Duration.ofSeconds(30));

        validate(properties);

        assertThat(statusPollIntervalWarnings()).isEmpty();
    }

    /**
     * `SameSite=None` requires the browser to send the cookie cross-site; without CSRF protection
     * that is an open door for cross-site request forgery.
     */
    @Test
    void warnsWhenSameSiteNoneWithCsrfDisabled() {
        SentretProperties properties = new SentretProperties();
        properties.setCookieSameSite("None");
        properties.setCsrfEnabled(false);

        validate(properties);

        assertThat(sameSiteWarnings()).hasSize(1);
    }

    @Test
    void doesNotWarnWhenSameSiteNoneWithCsrfEnabled() {
        SentretProperties properties = new SentretProperties();
        properties.setCookieSameSite("None");
        properties.setCsrfEnabled(true);

        validate(properties);

        assertThat(sameSiteWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnAboutHeartbeatOrWarningBeforeWhenMaxIdleIsNull() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(null);

        validate(properties);

        assertThat(heartbeatWarnings()).isEmpty();
        assertThat(warningBeforeWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnAboutHeartbeatOrWarningBeforeWhenMaxIdleIsNegative() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(Duration.ofMinutes(-1));

        validate(properties);

        assertThat(heartbeatWarnings()).isEmpty();
        assertThat(warningBeforeWarnings()).isEmpty();
    }
}
