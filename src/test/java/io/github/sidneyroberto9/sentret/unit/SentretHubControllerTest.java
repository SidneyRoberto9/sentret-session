package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.hub.SentretHubController;
import io.github.sidneyroberto9.sentret.hub.SentretHubStatusService;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionConfigResponse;
import io.github.sidneyroberto9.sentret.hub.dto.response.SessionStatusResponse;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SentretHubControllerTest {

    private static final SessionConfigResponse CONFIG = new SessionConfigResponse(60_000L, 30_000L, 60_000L, null);
    private static final SentretUser USER = new SentretUser(
            "user-1", "user@test.com", "sid", Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2029-12-31T23:00:00Z"));

    private SentretService sessionService;
    private SentretHubStatusService statusService;
    private SentretHubController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(SentretService.class);
        statusService = mock(SentretHubStatusService.class);
        controller = new SentretHubController(sessionService, statusService);
    }

    private static SessionStatusResponse authenticated() {
        return new SessionStatusResponse(true, "user-1", "user@test.com", 1_000L, 500L, CONFIG);
    }

    @Test
    void statusReturnsWhatTheStatusServiceBuilds() {
        when(statusService.status(USER)).thenReturn(authenticated());

        ResponseEntity<SessionStatusResponse> response = controller.status(USER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(authenticated());
    }

    @Test
    void heartbeatTouchesThenReportsTheRefreshedPrincipal() {
        SentretUser touched = new SentretUser("user-1", "user@test.com", "sid", USER.expiresAt(), Instant.parse("2030-01-01T00:00:00Z"));
        when(sessionService.touch(USER)).thenReturn(touched);
        when(statusService.status(touched)).thenReturn(authenticated());

        ResponseEntity<SessionStatusResponse> response = controller.heartbeat(USER);

        verify(sessionService).touch(USER);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(authenticated());
    }

    /** An app with its own chain may leave heartbeat permit-all: no principal must be a 401, not a 500. */
    @Test
    void heartbeatWithoutPrincipalReturns401WithoutTouching() {
        ResponseEntity<SessionStatusResponse> response = controller.heartbeat(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(sessionService);
    }

    @Test
    void renewWithoutPrincipalReturns401WithoutTouching() {
        ResponseEntity<SessionStatusResponse> response = controller.renew(null, mock(HttpServletResponse.class));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(sessionService);
    }

    @Test
    void renewReturnsTheRenewedStatus() {
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        SentretUser renewed = new SentretUser("user-1", "user@test.com", "sid", Instant.parse("2030-01-01T08:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"));
        when(sessionService.renew(USER, httpResponse)).thenReturn(renewed);
        when(statusService.status(renewed)).thenReturn(authenticated());

        ResponseEntity<SessionStatusResponse> response = controller.renew(USER, httpResponse);

        verify(sessionService).renew(USER, httpResponse);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(authenticated());
    }
}
