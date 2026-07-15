package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteUser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringSessionLiteUserTest {

    @Test
    void canonicalConstructorWithNullRolesYieldsEmptyImmutableList() {
        SpringSessionLiteUser user = new SpringSessionLiteUser("u1", "u1@example.com", "s1", null);

        assertThat(user.roles()).isEmpty();
        assertThatThrownBy(() -> user.roles().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void canonicalConstructorWithRolesYieldsDefensiveImmutableCopy() {
        List<String> input = new ArrayList<>(List.of("ADMIN", "USER"));

        SpringSessionLiteUser user = new SpringSessionLiteUser("u1", "u1@example.com", "s1", input);

        assertThat(user.roles()).containsExactly("ADMIN", "USER");
        assertThatThrownBy(() -> user.roles().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);

        input.add("MUTATED");
        assertThat(user.roles()).containsExactly("ADMIN", "USER");
    }

    @Test
    void secondaryConstructorDelegatesWithEmptyRoles() {
        SpringSessionLiteUser user = new SpringSessionLiteUser("u2", "u2@example.com", "s2");

        assertThat(user.userId()).isEqualTo("u2");
        assertThat(user.email()).isEqualTo("u2@example.com");
        assertThat(user.sessionId()).isEqualTo("s2");
        assertThat(user.roles()).isEmpty();
    }

    @Test
    void accessorsReturnConstructorValues() {
        SpringSessionLiteUser user = new SpringSessionLiteUser("u3", "u3@example.com", "s3", List.of("ROLE_A"));

        assertThat(user.userId()).isEqualTo("u3");
        assertThat(user.email()).isEqualTo("u3@example.com");
        assertThat(user.sessionId()).isEqualTo("s3");
        assertThat(user.roles()).containsExactly("ROLE_A");
    }

    @Test
    void equalsHashCodeAndToStringBehaveAsGeneratedByRecord() {
        SpringSessionLiteUser user1 = new SpringSessionLiteUser("u4", "u4@example.com", "s4", List.of("ROLE_A"));
        SpringSessionLiteUser user2 = new SpringSessionLiteUser("u4", "u4@example.com", "s4", List.of("ROLE_A"));
        SpringSessionLiteUser different = new SpringSessionLiteUser("u5", "u5@example.com", "s5", List.of("ROLE_B"));

        assertThat(user1).isEqualTo(user2);
        assertThat(user1.hashCode()).isEqualTo(user2.hashCode());
        assertThat(user1).isNotEqualTo(different);
        assertThat(user1.toString()).contains("SpringSessionLiteUser").contains("u4");
    }
}
