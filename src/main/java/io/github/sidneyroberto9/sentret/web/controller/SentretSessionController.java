package io.github.sidneyroberto9.sentret.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.service.SentretSessionRemaining;
import io.github.sidneyroberto9.sentret.web.SentretCurrentSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Opt-in {@code /session/*} endpoints (base path configurable via
 * {@code sentret.endpoints-base-path}), registered only when
 * {@code sentret.endpoints-enabled=true} (see
 * {@code SentretEndpointsAutoConfiguration}).
 *
 * <p>{@code /status} is permit-all (see the security-chain wiring in
 * {@code SentretAutoConfiguration#sentretSecurityFilterChain}); heartbeat/renew/logout
 * require authentication, enforced by the default security chain rather than by code here.
 *
 * <p>Activity tracking ({@code touch()}) is explicit and lives here:
 * {@link #heartbeat(SentretUser)} is the only endpoint that calls it. The authentication
 * filter (via {@code SentretService#validate}) deliberately does not touch, so that the
 * client's own {@code /status} poll and {@code /stream} connection cannot keep an idle session
 * alive forever.
 */
@RestController
@RequestMapping("${sentret.endpoints-base-path:/session}")
@RequiredArgsConstructor
public class SentretSessionController {

    private static final ErrorResponse UNAUTHORIZED_BODY = new ErrorResponse("unauthorized", "Authentication required");

    private final SentretService sessionService;
    private final SentretProperties properties;

    @GetMapping("/status")
    public ResponseEntity<SessionStatusResponse> status(@SentretCurrentSession SentretUser user) {
        return ResponseEntity.ok(buildStatus(user));
    }

    /**
     * The library's only user-activity signal. The client fires this from real DOM events
     * (throttled to {@code heartbeatInterval}), so this is the one place that resets the idle
     * window — {@code SentretService#validate} does not, or the client's own
     * {@code /status} poll would keep every session alive forever.
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<SessionStatusResponse> heartbeat(@SentretCurrentSession SentretUser user) {
        if (user == null) {
            return ResponseEntity.ok(buildStatus(null));
        }

        sessionService.touch(user.sessionId());

        return ResponseEntity.ok(buildStatus(user));
    }

    @PostMapping("/renew")
    public ResponseEntity<?> renew(HttpServletRequest request, HttpServletResponse response) {
        return sessionService.renew(request, response)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(buildStatus(user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(UNAUTHORIZED_BODY));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionService.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    private SessionStatusResponse buildStatus(SentretUser user) {
        SessionStatusResponse.Config config = buildConfig();

        if (user == null) {
            return anonymousStatus(config);
        }

        return sessionService.remaining(user.sessionId())
                .map(remaining -> authenticatedStatus(user, remaining, config))
                .orElseGet(() -> anonymousStatus(config));
    }

    private SessionStatusResponse authenticatedStatus(SentretUser user, SentretSessionRemaining remaining, SessionStatusResponse.Config config) {
        return new SessionStatusResponse(
                true,
                user.userId(),
                user.email(),
                user.roles(),
                remaining.absoluteRemainingMs(),
                remaining.idleRemainingMs(),
                config);
    }

    private SessionStatusResponse anonymousStatus(SessionStatusResponse.Config config) {
        return new SessionStatusResponse(false, null, null, null, null, null, config);
    }

    private SessionStatusResponse.Config buildConfig() {
        return new SessionStatusResponse.Config(
                properties.getTtl().toMillis(),
                properties.getMaxIdle() == null ? 0L : properties.getMaxIdle().toMillis(),
                properties.getHeartbeatInterval().toMillis(),
                properties.getStatusPollInterval().toMillis(),
                properties.getWarningBefore().toMillis(),
                properties.getLoginUrl(),
                properties.getLogoutUrl(),
                properties.getRedirectAfterExpiryUrl());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionStatusResponse(
            boolean authenticated,
            String userId,
            String email,
            List<String> roles,
            Long absoluteRemainingMs,
            Long idleRemainingMs,
            Config config
    ) {

        public record Config(
                long ttlMs,
                long maxIdleMs,
                long heartbeatIntervalMs,
                long statusPollIntervalMs,
                long warningBeforeMs,
                String loginUrl,
                String logoutUrl,
                String redirectAfterExpiryUrl
        ) {
        }
    }

    /**
     * Mirrors the {@code {"error":..., "message":...}} shape written by the
     * {@code AuthenticationEntryPoint} in {@code SentretAutoConfiguration}, for the rare
     * race where a session is deleted between the authentication filter and this controller
     * running (e.g. concurrent logout).
     */
    public record ErrorResponse(String error, String message) {
    }
}
