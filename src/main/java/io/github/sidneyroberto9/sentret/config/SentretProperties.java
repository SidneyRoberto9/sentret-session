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
     * Whether to update the last-accessed timestamp when activity is signalled. Since 2.1.1 the
     * only activity signal is {@code POST /session/heartbeat} (which the frontend client fires from
     * real DOM events): validation alone does not count, or the client's own {@code /status} poll
     * would keep every session alive forever and {@link #maxIdle} could never elapse.
     */
    private boolean updateLastAccessed = true;

    /**
     * @deprecated Since 2.1.2 this has no effect. It used to throttle last-accessed writes back when
     * every authenticated request touched the session. Now only {@code POST /session/heartbeat}
     * does, and the client already throttles that to {@link #heartbeatInterval} — so throttling
     * again here only discarded real activity and logged active users out. Kept so existing
     * configuration keeps binding; remove it from your properties.
     */
    @Deprecated(since = "2.1.2", forRemoval = true)
    private Duration lastAccessedThrottle = Duration.ofMinutes(5);

    /**
     * Slide the expiration forward on activity (bounded by the same throttle as last-accessed).
     * "Activity" means a heartbeat — see {@link #updateLastAccessed}.
     */
    private boolean slidingExpiration = false;

    /**
     * Maximum inactivity window before a session is considered idle-expired, evaluated in
     * {@code validate()} against {@code lastAccessedAt}. {@code null} or {@link Duration#ZERO}
     * disables idle enforcement (default), preserving pre-2.1 behavior.
     *
     * <p>Requires an activity signal to be useful: {@code lastAccessedAt} only moves on
     * {@code POST /session/heartbeat} (see {@link #updateLastAccessed}). Enable this only for apps
     * running the frontend client, or send the heartbeat yourself. Keep {@link #heartbeatInterval}
     * well below this value — the client throttles heartbeats to that interval, so an interval
     * close to {@code maxIdle} logs active users out. The library warns at startup when they are
     * too close, or when {@link #warningBefore} is {@code >= maxIdle}.
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

}
