package io.github.sidneyroberto9.sentret.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Every property has a safe default: the library works with no configuration at all.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "sentret")
public class SentretProperties {

    private boolean enabled = true;

    /** Absolute session lifetime. */
    private Duration ttl = Duration.ofHours(8);

    /**
     * Inactivity window, measured from the last heartbeat. {@code null}, zero or negative disables
     * it. Keep {@code hub.heartbeat-interval} well below it.
     */
    private Duration maxIdle = Duration.ofMinutes(30);

    /** Session cookie name. Prefix it with {@code __Host-} (e.g. {@code __Host-SID}) to harden it. */
    private String cookieName = "SENTRETSID";

    /** Send the cookie over HTTPS only. Set {@code false} for local development over plain HTTP. */
    private boolean cookieSecure = true;

    private String cookieSameSite = "Lax";

    /** Share the cookie across subdomains, e.g. {@code example.com}. */
    private String cookieDomain;

    /** CSRF protection on the default security chain. Keep SameSite=Lax/Strict when disabled. */
    private boolean csrfEnabled = false;

    /** Origins allowed to call the API with the session cookie. CORS is on when this is not empty. */
    private List<String> corsAllowedOrigins = new ArrayList<>();

    private List<String> permitAllPaths = new ArrayList<>(List.of("/login", "/auth/**", "/public/**"));

    private final Hub hub = new Hub();

    /** Whether {@link #maxIdle} is enforced: {@code null}, zero or negative disables it. */
    public boolean isIdleEnabled() {
        return maxIdle != null && !maxIdle.isZero() && !maxIdle.isNegative();
    }

    /**
     * Inactivity hub consumed by the {@code @media4all/session-lite} client. Only read when
     * {@code sentret.hub.enabled=true}.
     */
    @Getter
    @Setter
    public static class Hub {

        private boolean enabled = false;

        private String basePath = "/session";

        /** How often the client sends a heartbeat. Echoed to the client. */
        private Duration heartbeatInterval = Duration.ofSeconds(60);

        /** How often the client polls the status. Echoed to the client. */
        private Duration statusPollInterval = Duration.ofSeconds(30);

        /** How long before expiry the client shows the warning. Echoed to the client. */
        private Duration warningBefore = Duration.ofSeconds(60);

        /** Where the client sends the user when the session ends. Echoed to the client. */
        private String loginUrl;
    }
}
