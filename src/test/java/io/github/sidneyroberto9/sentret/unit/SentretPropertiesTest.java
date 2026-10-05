package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SentretPropertiesTest {

    private static SentretProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("sentret", SentretProperties.class);
    }

    @Test
    void worksWithZeroConfiguration() {
        SentretProperties properties = bind(Map.of());

        assertThat(properties.isCreateTable()).isTrue();
        assertThat(properties.getTtl()).isEqualTo(Duration.ofHours(8));
        assertThat(properties.getMaxIdle()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getCookieName()).isEqualTo("SENTRETSID");
        assertThat(properties.isCookieSecure()).isTrue();
        assertThat(properties.getCookieSameSite()).isEqualTo("Lax");
        assertThat(properties.isCsrfEnabled()).isFalse();
        assertThat(properties.getCorsAllowedOrigins()).isEmpty();
        assertThat(properties.getHub().isEnabled()).isFalse();
        assertThat(properties.getHub().getBasePath()).isEqualTo("/session");
        assertThat(properties.getPermitAllPaths()).contains("/actuator/health/**");
    }

    @Test
    void hubPropertiesBindUnderTheHubPrefix() {
        SentretProperties properties = bind(Map.of(
                "sentret.hub.enabled", "true",
                "sentret.hub.base-path", "/api/lite/session",
                "sentret.hub.heartbeat-interval", "30s",
                "sentret.hub.login-url", "https://login.example.com"));

        assertThat(properties.getHub().isEnabled()).isTrue();
        assertThat(properties.getHub().getBasePath()).isEqualTo("/api/lite/session");
        assertThat(properties.getHub().getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getHub().getLoginUrl()).isEqualTo("https://login.example.com");
    }

    /** Pins the public configuration surface: anything new here is a deliberate decision. */
    @Test
    void coreConfigurationSurfaceIsExactlyTheseProperties() {
        // JaCoCo adds a synthetic $jacocoData field when coverage is on.
        assertThat(Arrays.stream(SentretProperties.class.getDeclaredFields()).filter(field -> !field.isSynthetic()).map(Field::getName))
                .containsExactlyInAnyOrder(
                        "enabled", "createTable", "ttl", "maxIdle", "cookieName", "cookieSecure", "cookieSameSite",
                        "cookieDomain", "csrfEnabled", "csrfIgnoredPaths", "corsAllowedOrigins", "permitAllPaths", "hub");
    }
}
