package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SentretUserTest {

    @Test
    void canonicalConstructorWithNullRolesYieldsEmptyImmutableList() {
        SentretUser user = new SentretUser("u1", "u1@example.com", "s1", null);

        assertThat(user.roles()).isEmpty();
        assertThatThrownBy(() -> user.roles().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void canonicalConstructorWithRolesYieldsDefensiveImmutableCopy() {
        List<String> input = new ArrayList<>(List.of("ADMIN", "USER"));

        SentretUser user = new SentretUser("u1", "u1@example.com", "s1", input);

        assertThat(user.roles()).containsExactly("ADMIN", "USER");
        assertThatThrownBy(() -> user.roles().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);

        input.add("MUTATED");
        assertThat(user.roles()).containsExactly("ADMIN", "USER");
    }

    @Test
    void secondaryConstructorDelegatesWithEmptyRoles() {
        SentretUser user = new SentretUser("u2", "u2@example.com", "s2");

        assertThat(user.userId()).isEqualTo("u2");
        assertThat(user.email()).isEqualTo("u2@example.com");
        assertThat(user.sessionId()).isEqualTo("s2");
        assertThat(user.roles()).isEmpty();
    }

    @Test
    void accessorsReturnConstructorValues() {
        SentretUser user = new SentretUser("u3", "u3@example.com", "s3", List.of("ROLE_A"));

        assertThat(user.userId()).isEqualTo("u3");
        assertThat(user.email()).isEqualTo("u3@example.com");
        assertThat(user.sessionId()).isEqualTo("s3");
        assertThat(user.roles()).containsExactly("ROLE_A");
    }

    @Test
    void equalsHashCodeAndToStringBehaveAsGeneratedByRecord() {
        SentretUser user1 = new SentretUser("u4", "u4@example.com", "s4", List.of("ROLE_A"));
        SentretUser user2 = new SentretUser("u4", "u4@example.com", "s4", List.of("ROLE_A"));
        SentretUser different = new SentretUser("u5", "u5@example.com", "s5", List.of("ROLE_B"));

        assertThat(user1).isEqualTo(user2);
        assertThat(user1.hashCode()).isEqualTo(user2.hashCode());
        assertThat(user1).isNotEqualTo(different);
        assertThat(user1.toString()).contains("SentretUser").contains("u4");
    }
}
