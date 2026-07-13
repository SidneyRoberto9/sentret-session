package io.github.sidneyroberto9.spring_session_lite.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "spring-session-lite")
public class SpringSessionLiteProperties {

    /**
     * Default value of {@link #ipHashSalt}. If left unchanged while {@link #cookieSecure} is
     * enabled, the library warns at startup (see SpringSessionLiteSecurityValidator).
     */
    public static final String DEFAULT_IP_HASH_SALT = "spring-session-lite";

    private boolean enabled = true;

    private String cookieName = "SLSID";

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

    private int sessionIdLength = 21;

    /**
     * Salt used in HMAC-SHA256 IP hashing. Override in production with a strong secret.
     */
    private String ipHashSalt = DEFAULT_IP_HASH_SALT;

    /**
     * Trust X-Forwarded-For header for client IP resolution. Enable only behind a trusted proxy.
     */
    private boolean trustForwardedFor = false;

    /**
     * Number of trusted proxies that append to X-Forwarded-For. The client IP is taken at
     * position (count from the right), never the spoofable left-most entry.
     */
    private int trustedProxyCount = 1;

    /**
     * Whether to update last-accessed timestamp on each successful validation.
     */
    private boolean updateLastAccessed = true;

    /**
     * Minimum interval between last-accessed writes (throttling), to avoid a DB write per request.
     */
    private Duration lastAccessedThrottle = Duration.ofMinutes(5);

    /**
     * Slide the expiration forward on activity (bounded by the same throttle as last-accessed).
     */
    private boolean slidingExpiration = false;

    /**
     * Maximum inactivity window before a session is considered idle-expired, evaluated in
     * {@code validate()} against {@code lastAccessedAt}. {@code null} or {@link Duration#ZERO}
     * disables idle enforcement (default), preserving pre-2.1 behavior. When enabled, the
     * effective last-accessed throttle is internally capped at {@code maxIdle / 2} regardless of
     * {@link #lastAccessedThrottle}, so idle detection stays accurate to within half the idle
     * window.
     */
    private Duration maxIdle = Duration.ZERO;

    /**
     * How often the frontend client should send an activity heartbeat. Config-echo only — the
     * library serves this via the session status endpoint and does not enforce it.
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
}
