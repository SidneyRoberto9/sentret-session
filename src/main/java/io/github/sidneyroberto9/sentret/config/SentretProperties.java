package io.github.sidneyroberto9.sentret.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "sentret")
public class SentretProperties {


    private boolean enabled = true;

    private String cookieName = "SENTRETSID";

    private Duration ttl = Duration.ofHours(8);

    private boolean cookieSecure = true;

    private String cookieSameSite = "Lax";

    private String cookiePath = "/";

    private String cookieDomain;

    /**
     * Optional cookie name prefix. Use {@code __Host-} or {@code __Secure-} to harden the
     * cookie. {@code __Host-} requires cookieSecure=true, cookiePath="/" and no cookieDomain.
     */
    private String cookiePrefix = "";




    /**
     * Inactivity window, measured from the last heartbeat. {@code null}, zero or negative disables
     * it. Keep {@link #heartbeatInterval} well below it.
     */
    private Duration maxIdle = Duration.ZERO;

    /**
     * How often the frontend client should send an activity heartbeat. Config-echo only — the
     * library serves this via the session status endpoint and does not enforce it. Keep it
     * comfortably below {@link #maxIdle}: since 2.1.1 the heartbeat is the only thing that resets
     * the idle window, so a client that stops heartbeating goes idle even while in use.
     */
    private Duration heartbeatInterval = Duration.ofSeconds(60);

    /**
     * How often the frontend client should poll the session status endpoint. Config-echo only.
     */
    private Duration statusPollInterval = Duration.ofSeconds(30);

    /**
     * How long before idle/absolute expiry the frontend client should show a warning. Config-echo
     * only.
     */
    private Duration warningBefore = Duration.ofSeconds(60);

    /**
     * URL the frontend client should redirect to for (re)authentication. Config-echo only.
     */
    private String loginUrl;

    /**
     * URL the frontend client should call/redirect to on explicit logout. Config-echo only.
     */
    private String logoutUrl;

    /**
     * URL the frontend client should redirect to after session expiry (idle or absolute).
     * Config-echo only; falls back to {@link #loginUrl} when unset.
     */
    private String redirectAfterExpiryUrl;

    /**
     * Enable CSRF protection on the default security chain. Cookie-based auth is CSRF-sensitive;
     * keep SameSite=Lax/Strict when this is disabled.
     */
    private boolean csrfEnabled = false;

    /**
     * Enable CORS on the default security chain (required for cross-origin cookie auth).
     */
    private boolean corsEnabled = false;

    private List<String> corsAllowedOrigins = new ArrayList<>();

    private List<String> corsAllowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

    private boolean corsAllowCredentials = true;

    /**
     * Register the scheduled expired-session cleanup task.
     */
    private boolean cleanupEnabled = true;

    private String cleanupCron = "0 */30 * * * *";

    private List<String> permitAllPaths = new ArrayList<>(List.of("/login", "/auth/**", "/public/**"));

    /**
     * Register the opt-in {@code /session/*} REST endpoints (status/heartbeat/renew/logout).
     * Off by default; existing consumers are unaffected until they explicitly enable this.
     */
    private boolean endpointsEnabled = false;

    /**
     * Base path for the opt-in session endpoints, used only when {@link #endpointsEnabled} is
     * {@code true}.
     */
    private String endpointsBasePath = "/session";

    /** Whether {@link #maxIdle} is enforced: {@code null}, zero or negative disables it. */
    public boolean isIdleEnabled() {
        return maxIdle != null && !maxIdle.isZero() && !maxIdle.isNegative();
    }
}
