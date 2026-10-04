package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.web.SentretCurrentSession;
import io.github.sidneyroberto9.sentret.web.SentretCurrentSessionArgumentResolver;
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
 * Covers {@link SentretCurrentSessionArgumentResolver}: {@code supportsParameter}
 * (annotation + assignable-type gate) and {@code resolveArgument} (pulling the
 * {@link SentretUser} principal straight out of {@link SecurityContextHolder}, with no
 * dependency on {@code SentretUserService} or any other collaborator).
 */
class SentretCurrentSessionArgumentResolverTest {

    private final SentretCurrentSessionArgumentResolver resolver = new SentretCurrentSessionArgumentResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void annotatedCorrectType(@SentretCurrentSession SentretUser user) {
        // signature only, used to obtain a MethodParameter via reflection
    }

    private void annotatedWrongType(@SentretCurrentSession String user) {
        // signature only, used to obtain a MethodParameter via reflection
    }

    private void notAnnotatedCorrectType(SentretUser user) {
        // signature only, used to obtain a MethodParameter via reflection
    }

    private MethodParameter parameterOf(String methodName, Class<?> paramType) throws NoSuchMethodException {
        Method method = SentretCurrentSessionArgumentResolverTest.class.getDeclaredMethod(methodName, paramType);
        return new MethodParameter(method, 0);
    }

    // --- supportsParameter ---

    @Test
    void supportsParameterIsTrueWhenAnnotatedWithCorrectType() throws Exception {
        MethodParameter parameter = parameterOf("annotatedCorrectType", SentretUser.class);

        assertThat(resolver.supportsParameter(parameter)).isTrue();
    }

    @Test
    void supportsParameterIsFalseWhenAnnotatedButWrongType() throws Exception {
        MethodParameter parameter = parameterOf("annotatedWrongType", String.class);

        assertThat(resolver.supportsParameter(parameter)).isFalse();
    }

    @Test
    void supportsParameterIsFalseWhenCorrectTypeButNotAnnotated() throws Exception {
        MethodParameter parameter = parameterOf("notAnnotatedCorrectType", SentretUser.class);

        assertThat(resolver.supportsParameter(parameter)).isFalse();
    }

    // --- resolveArgument ---

    @Test
    void resolveArgumentReturnsUserWhenPrincipalIsSentretUser() {
        SentretUser user = new SentretUser("user-1", "user@test.com", "sid-1", null, null);
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
    void resolveArgumentReturnsNullWhenPrincipalIsNotSentretUser() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("anonymousUser");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        Object resolved = resolver.resolveArgument(null, null, null, null);

        assertThat(resolved).isNull();
    }
}
