package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import io.github.sidneyroberto9.spring_session_lite.web.SpringSessionLiteCurrentSession;
import io.github.sidneyroberto9.spring_session_lite.web.SpringSessionLiteCurrentSessionArgumentResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link SpringSessionLiteCurrentSessionArgumentResolver}: {@code supportsParameter}
 * (annotation + assignable-type gate) and {@code resolveArgument} (pulling the
 * {@link SpringSessionLiteUser} principal straight out of {@link SecurityContextHolder}, with no
 * dependency on {@code SpringSessionLiteUserService} or any other collaborator).
 */
class SpringSessionLiteCurrentSessionArgumentResolverTest {

    private final SpringSessionLiteCurrentSessionArgumentResolver resolver = new SpringSessionLiteCurrentSessionArgumentResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void annotatedCorrectType(@SpringSessionLiteCurrentSession SpringSessionLiteUser user) {
        // signature only, used to obtain a MethodParameter via reflection
    }

    private void annotatedWrongType(@SpringSessionLiteCurrentSession String user) {
        // signature only, used to obtain a MethodParameter via reflection
    }

    private void notAnnotatedCorrectType(SpringSessionLiteUser user) {
        // signature only, used to obtain a MethodParameter via reflection
    }

    private MethodParameter parameterOf(String methodName, Class<?> paramType) throws NoSuchMethodException {
        Method method = SpringSessionLiteCurrentSessionArgumentResolverTest.class.getDeclaredMethod(methodName, paramType);
        return new MethodParameter(method, 0);
    }

    // --- supportsParameter ---

    @Test
    void supportsParameterIsTrueWhenAnnotatedWithCorrectType() throws Exception {
        MethodParameter parameter = parameterOf("annotatedCorrectType", SpringSessionLiteUser.class);

        assertThat(resolver.supportsParameter(parameter)).isTrue();
    }

    @Test
    void supportsParameterIsFalseWhenAnnotatedButWrongType() throws Exception {
        MethodParameter parameter = parameterOf("annotatedWrongType", String.class);

        assertThat(resolver.supportsParameter(parameter)).isFalse();
    }

    @Test
    void supportsParameterIsFalseWhenCorrectTypeButNotAnnotated() throws Exception {
        MethodParameter parameter = parameterOf("notAnnotatedCorrectType", SpringSessionLiteUser.class);

        assertThat(resolver.supportsParameter(parameter)).isFalse();
    }

    // --- resolveArgument ---

    @Test
    void resolveArgumentReturnsUserWhenPrincipalIsSpringSessionLiteUser() {
        SpringSessionLiteUser user = new SpringSessionLiteUser("user-1", "user@test.com", "sid-1", List.of("ADMIN"));
        Authentication authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        Object resolved = resolver.resolveArgument(null, null, null, null);

        assertThat(resolved).isEqualTo(user);
    }

    @Test
    void resolveArgumentReturnsNullWhenNoAuthenticationPresent() {
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        Object resolved = resolver.resolveArgument(null, null, null, null);

        assertThat(resolved).isNull();
    }

    @Test
    void resolveArgumentReturnsNullWhenPrincipalIsNotSpringSessionLiteUser() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("anonymousUser");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        Object resolved = resolver.resolveArgument(null, null, null, null);

        assertThat(resolved).isNull();
    }
}
