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

    private List<ILoggingEvent> throttleWarnings() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("last-accessed-throttle"))
                .toList();
    }

    @Test
    void warnsWhenThrottleEqualsHalfMaxIdle() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.setLastAccessedThrottle(Duration.ofMinutes(5)); // == maxIdle / 2

        validate(properties);

        assertThat(throttleWarnings()).hasSize(1);
    }

    @Test
    void warnsWhenThrottleExceedsHalfMaxIdle() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(4));
        properties.setLastAccessedThrottle(Duration.ofMinutes(5)); // > maxIdle / 2 (2m)

        validate(properties);

        assertThat(throttleWarnings()).hasSize(1);
    }

    @Test
    void doesNotWarnWhenThrottleIsBelowHalfMaxIdle() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(Duration.ofMinutes(10));
        properties.setLastAccessedThrottle(Duration.ofMinutes(4)); // < maxIdle / 2 (5m)

        validate(properties);

        assertThat(throttleWarnings()).isEmpty();
    }

    @Test
    void doesNotWarnWhenMaxIdleDisabled() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties(); // maxIdle = ZERO
        properties.setLastAccessedThrottle(Duration.ofMinutes(5));

        validate(properties);

        assertThat(throttleWarnings()).isEmpty();
    }
}
