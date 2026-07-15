package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteSessionRemaining;
import io.github.sidneyroberto9.spring_session_lite.web.controller.SpringSessionLiteSessionController;
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

class SpringSessionLiteSessionControllerTest {

    private SpringSessionLiteService sessionService;
    private SpringSessionLiteSessionController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(SpringSessionLiteService.class);
        controller = new SpringSessionLiteSessionController(sessionService, new SpringSessionLiteProperties());
    }

    @Test
    void heartbeatReturnsAnonymousStatusAndSkipsTouchWhenUserIsNull() {
        ResponseEntity<SpringSessionLiteSessionController.SessionStatusResponse> response = controller.heartbeat(null);

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
        assertThat(response.getBody()).isEqualTo(new SpringSessionLiteSessionController.ErrorResponse("unauthorized", "Authentication required"));
    }

    @Test
    void statusFallsBackToAnonymousWhenSessionVanishesBetweenAuthAndRemaining() {
        SpringSessionLiteUser user = new SpringSessionLiteUser("user-1", "user@test.com", "sid-1", List.of());
        when(sessionService.remaining("sid-1")).thenReturn(Optional.empty());

        ResponseEntity<SpringSessionLiteSessionController.SessionStatusResponse> response = controller.status(user);

        assertThat(response.getBody().authenticated()).isFalse();
        assertThat(response.getBody().userId()).isNull();
    }

    @Test
    void statusReturnsAuthenticatedStatusWhenSessionIsPresent() {
        SpringSessionLiteUser user = new SpringSessionLiteUser("user-1", "user@test.com", "sid-1", List.of("ADMIN"));
        when(sessionService.remaining("sid-1")).thenReturn(Optional.of(new SpringSessionLiteSessionRemaining(60_000L, 30_000L)));

        ResponseEntity<SpringSessionLiteSessionController.SessionStatusResponse> response = controller.status(user);

        assertThat(response.getBody().authenticated()).isTrue();
        assertThat(response.getBody().userId()).isEqualTo("user-1");
        assertThat(response.getBody().roles()).containsExactly("ADMIN");
        assertThat(response.getBody().absoluteRemainingMs()).isEqualTo(60_000L);
        assertThat(response.getBody().idleRemainingMs()).isEqualTo(30_000L);
    }

    @Test
    void heartbeatTouchesSessionAndReturnsStatusWhenUserPresent() {
        SpringSessionLiteUser user = new SpringSessionLiteUser("user-1", "user@test.com", "sid-1", List.of());
        when(sessionService.remaining("sid-1")).thenReturn(Optional.of(new SpringSessionLiteSessionRemaining(60_000L, null)));

        ResponseEntity<SpringSessionLiteSessionController.SessionStatusResponse> response = controller.heartbeat(user);

        verify(sessionService).touch("sid-1");
        assertThat(response.getBody().authenticated()).isTrue();
    }

    @Test
    void renewReturnsStatusWhenServiceRenews() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        SpringSessionLiteUser user = new SpringSessionLiteUser("user-1", "user@test.com", "sid-1", List.of());
        when(sessionService.renew(request, httpResponse)).thenReturn(Optional.of(user));
        when(sessionService.remaining("sid-1")).thenReturn(Optional.of(new SpringSessionLiteSessionRemaining(60_000L, null)));

        ResponseEntity<?> response = controller.renew(request, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void statusConfigReportsZeroMaxIdleMsWhenMaxIdleIsNull() {
        SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
        properties.setMaxIdle(null);
        controller = new SpringSessionLiteSessionController(sessionService, properties);

        ResponseEntity<SpringSessionLiteSessionController.SessionStatusResponse> response = controller.status(null);

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
