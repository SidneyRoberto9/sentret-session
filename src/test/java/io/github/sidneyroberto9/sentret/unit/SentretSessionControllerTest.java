package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.service.SentretSessionRemaining;
import io.github.sidneyroberto9.sentret.web.controller.SentretSessionController;
import io.github.sidneyroberto9.sentret.web.controller.SentretSessionController.SessionStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SentretSessionControllerTest {

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant LAST_ACCESSED_AT = Instant.parse("2029-12-31T23:00:00Z");

    private SentretService sessionService;
    private SentretSessionController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(SentretService.class);
        controller = new SentretSessionController(sessionService, new SentretProperties());
    }

    private static SentretUser user() {
        return new SentretUser("user-1", "user@test.com", "sid-1", EXPIRES_AT, LAST_ACCESSED_AT);
    }

    @Test
    void statusIsComputedFromThePrincipal() {
        SentretUser user = user();
        when(sessionService.remaining(user)).thenReturn(new SentretSessionRemaining(60_000L, 30_000L));

        ResponseEntity<SessionStatusResponse> response = controller.status(user);

        assertThat(response.getBody().authenticated()).isTrue();
        assertThat(response.getBody().userId()).isEqualTo("user-1");
        assertThat(response.getBody().absoluteRemainingMs()).isEqualTo(60_000L);
        assertThat(response.getBody().idleRemainingMs()).isEqualTo(30_000L);
    }

    @Test
    void statusIsAnonymousWithoutPrincipal() {
        ResponseEntity<SessionStatusResponse> response = controller.status(null);

        assertThat(response.getBody().authenticated()).isFalse();
        verifyNoInteractions(sessionService);
    }

    @Test
    void heartbeatTouchesAndReportsTheRefreshedPrincipal() {
        SentretUser user = user();
        SentretUser touched = new SentretUser("user-1", "user@test.com", "sid-1", EXPIRES_AT, EXPIRES_AT.minusSeconds(60));
        when(sessionService.touch(user)).thenReturn(touched);
        when(sessionService.remaining(touched)).thenReturn(new SentretSessionRemaining(60_000L, 600_000L));

        ResponseEntity<SessionStatusResponse> response = controller.heartbeat(user);

        verify(sessionService).touch(user);
        assertThat(response.getBody().idleRemainingMs()).isEqualTo(600_000L);
    }

    @Test
    void heartbeatWithoutPrincipalIsAnonymousAndDoesNotTouch() {
        ResponseEntity<SessionStatusResponse> response = controller.heartbeat(null);

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
    }

    @Test
    void renewReturnsStatusWhenServiceRenews() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        SentretUser user = user();
        when(sessionService.renew(request, httpResponse)).thenReturn(Optional.of(user));
        when(sessionService.remaining(user)).thenReturn(new SentretSessionRemaining(60_000L, null));

        ResponseEntity<?> response = controller.renew(request, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void statusConfigReportsZeroMaxIdleMsWhenMaxIdleIsNull() {
        SentretProperties properties = new SentretProperties();
        properties.setMaxIdle(null);
        controller = new SentretSessionController(sessionService, properties);

        ResponseEntity<SessionStatusResponse> response = controller.status(null);

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
