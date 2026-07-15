package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SpringSessionLiteUserServiceTest {

    private final SpringSessionLiteUserService service = new SpringSessionLiteUserService();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsEmptyWhenNoAuthentication() {
        SecurityContextHolder.setContext(new SecurityContextImpl(null));

        Optional<SpringSessionLiteUser> result = service.currentUser();

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenPrincipalIsNotSpringSessionLiteUser() {
        User principal = new User("john", "password", List.of());
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new TestingAuthenticationToken(principal, null)));

        Optional<SpringSessionLiteUser> result = service.currentUser();

        assertThat(result).isEmpty();
    }

    @Test
    void returnsUserWhenPrincipalIsSpringSessionLiteUser() {
        SpringSessionLiteUser principal = new SpringSessionLiteUser("user-1", "john@example.com", "session-1", List.of("ROLE_USER"));
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new TestingAuthenticationToken(principal, null)));

        Optional<SpringSessionLiteUser> result = service.currentUser();

        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(principal);
        assertThat(result.get().userId()).isEqualTo("user-1");
        assertThat(result.get().email()).isEqualTo("john@example.com");
        assertThat(result.get().sessionId()).isEqualTo("session-1");
        assertThat(result.get().roles()).containsExactly("ROLE_USER");
    }
}
