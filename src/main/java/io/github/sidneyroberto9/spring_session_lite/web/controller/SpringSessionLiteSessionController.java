package io.github.sidneyroberto9.spring_session_lite.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteSessionRemaining;
import io.github.sidneyroberto9.spring_session_lite.web.SpringSessionLiteCurrentSession;
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
 * {@code spring-session-lite.endpoints-base-path}), registered only when
 * {@code spring-session-lite.endpoints-enabled=true} (see
 * {@code SpringSessionLiteEndpointsAutoConfiguration}).
 *
 * <p>{@code /status} is permit-all (see the security-chain wiring in
 * {@code SpringSessionLiteAutoConfiguration#sessionLiteSecurityFilterChain}); heartbeat/renew/logout
 * require authentication, enforced by the default security chain rather than by code here.
 *
 * <p>Activity tracking ({@code touch()}) already happens on every authenticated request inside
 * {@code SpringSessionLiteAuthenticationFilter} (via {@code SpringSessionLiteService#validate}), so
 * {@link #heartbeat(SpringSessionLiteUser)} does not call it again — it only reports the status
 * that resulted from the filter's touch earlier in this same request.
 */
@RestController
@RequestMapping("${spring-session-lite.endpoints-base-path:/session}")
@RequiredArgsConstructor
public class SpringSessionLiteSessionController {

    private static final ErrorResponse UNAUTHORIZED_BODY = new ErrorResponse("unauthorized", "Authentication required");

    private final SpringSessionLiteService sessionService;
    private final SpringSessionLiteProperties properties;

    @GetMapping("/status")
    public ResponseEntity<SessionStatusResponse> status(@SpringSessionLiteCurrentSession SpringSessionLiteUser user) {
        return ResponseEntity.ok(buildStatus(user));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<SessionStatusResponse> heartbeat(@SpringSessionLiteCurrentSession SpringSessionLiteUser user) {
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

    private SessionStatusResponse buildStatus(SpringSessionLiteUser user) {
        SessionStatusResponse.Config config = buildConfig();

        if (user == null) {
            return anonymousStatus(config);
        }

        return sessionService.remaining(user.sessionId())
                .map(remaining -> authenticatedStatus(user, remaining, config))
                .orElseGet(() -> anonymousStatus(config));
    }

    private SessionStatusResponse authenticatedStatus(SpringSessionLiteUser user, SpringSessionLiteSessionRemaining remaining, SessionStatusResponse.Config config) {
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
     * {@code AuthenticationEntryPoint} in {@code SpringSessionLiteAutoConfiguration}, for the rare
     * race where a session is deleted between the authentication filter and this controller
     * running (e.g. concurrent logout).
     */
    public record ErrorResponse(String error, String message) {
    }
}
