package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.service.SentretSessionRemaining;
import io.github.sidneyroberto9.sentret.web.controller.SentretSessionController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SentretSessionControllerTest {

    private SentretService sessionService;
    private SentretSessionController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(SentretService.class);
        controller = new SentretSessionController(sessionService, new SentretProperties());
    }

    @Test
    void heartbeatReturnsAnonymousStatusAndSkipsTouchWhenUserIsNull() {
        ResponseEntity<SentretSessionController.SessionStatusResponse> response = controller.heartbeat(null);

        assertThat(response.getBody().authenticated()).isFalse();
        verify(sessionService, never()).touch(any());
    }

    @Test
    void renewReturnsUnauthorizedWhenServiceReturnsEmpty() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        when(sessionService.renew(request, httpResponse)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.renew(request, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(new SentretSessionController.ErrorResponse("unauthorized", "Authentication required"));
    }

    @Test
    void statusFallsBackToAnonymousWhenSessionVanishesBetweenAuthAndRemaining() {
        SentretUser user = new SentretUser("user-1", "user@test.com", "sid-1");
        when(sessionService.remaining("sid-1")).thenReturn(Optional.empty());

        ResponseEntity<SentretSessionController.SessionStatusResponse> response = controller.status(user);

        assertThat(response.getBody().authenticated()).isFalse();
        assertThat(response.getBody().userId()).isNull();
    }

    @Test
    void statusReturnsAuthenticatedStatusWhenSessionIsPresent() {
        SentretUser user = new SentretUser("user-1", "user@test.com", "sid-1");
        when(sessionService.remaining("sid-1")).thenReturn(Optional.of(new SentretSessionRemaining(60_000L, 30_000L)));

        ResponseEntity<SentretSessionController.SessionStatusResponse> response = controller.status(user);

        assertThat(response.getBody().authenticated()).isTrue();
        assertThat(response.getBody().userId()).isEqualTo("user-1");
        assertThat(response.getBody().absoluteRemainingMs()).isEqualTo(60_000L);
        assertThat(response.getBody().idleRemainingMs()).isEqualTo(30_000L);
    }

    @Test
    void heartbeatTouchesSessionAndReturnsStatusWhenUserPresent() {
        SentretUser user = new SentretUser("user-1", "user@test.com", "sid-1");
        when(sessionService.remaining("sid-1")).thenReturn(Optional.of(new SentretSessionRemaining(60_000L, null)));

        ResponseEntity<SentretSessionController.SessionStatusResponse> response = controller.heartbeat(user);

        verify(sessionService).touch("sid-1");
        assertThat(response.getBody().authenticated()).isTrue();
    }

    @Test
    void renewReturnsStatusWhenServiceRenews() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        SentretUser user = new SentretUser("user-1", "user@test.com", "sid-1");
        when(sessionService.renew(request, httpResponse)).thenReturn(Optional.of(user));
        when(sessionService.remaining("sid-1")).thenReturn(Optional.of(new SentretSessionRemaining(60_000L, null)));

        ResponseEntity<?> response = controller.renew(request, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void statusConfigReportsZeroMaxIdleMsWhenMaxIdleIsNull() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(null);
        controller = new SentretSessionController(sessionService, properties);

        ResponseEntity<SentretSessionController.SessionStatusResponse> response = controller.status(null);

        assertThat(response.getBody().config().maxIdleMs()).isZero();
    }

    @Test
    void logoutDelegatesToServiceAndReturnsNoContent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);

        ResponseEntity<Void> response = controller.logout(request, httpResponse);

        verify(sessionService).logout(request, httpResponse);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
