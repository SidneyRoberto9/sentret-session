package io.github.sidneyroberto9.sentret.hub;

import io.github.sidneyroberto9.sentret.hub.dto.response.SessionStatusResponse;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Inactivity hub endpoints consumed by the @media4all/session-lite client. HTTP only: the rules
 * live in {@link SentretService} and the body in {@link SentretHubStatusService}. {@code status} is
 * permit-all; {@code heartbeat} and {@code renew} require a session (enforced by the security chain).
 */
@RestController
@RequestMapping("${sentret.hub.base-path:/session}")
@RequiredArgsConstructor
public class SentretHubController {

    private final SentretService sessionService;
    private final SentretHubStatusService statusService;

    @GetMapping("/status")
    public ResponseEntity<SessionStatusResponse> status(@AuthenticationPrincipal SentretUser user) {
        return ResponseEntity.status(HttpStatus.OK).body(statusService.status(user));
    }

    /** The only user-activity signal: the client sends it from real DOM events. */
    @PostMapping("/heartbeat")
    public ResponseEntity<SessionStatusResponse> heartbeat(@AuthenticationPrincipal SentretUser user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        SentretUser touched = sessionService.touch(user);
        return ResponseEntity.status(HttpStatus.OK).body(statusService.status(touched));
    }

    @PostMapping("/renew")
    public ResponseEntity<SessionStatusResponse> renew(HttpServletRequest request, HttpServletResponse response) {
        Optional<SentretUser> renewed = sessionService.renew(request, response);

        if (renewed.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.status(HttpStatus.OK).body(statusService.status(renewed.get()));
    }
}
