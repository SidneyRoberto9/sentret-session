package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

class SentretUserTest {

    /** Roles are the host application's concern; the principal only identifies the user. */
    @Test
    void principalCarriesOnlyIdentity() {
        assertThat(SentretUser.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("userId", "email", "sessionId");
    }

    @Test
    void accessorsReturnConstructorValues() {
        SentretUser user = new SentretUser("u1", "u1@example.com", "s1");

        assertThat(user.userId()).isEqualTo("u1");
        assertThat(user.email()).isEqualTo("u1@example.com");
        assertThat(user.sessionId()).isEqualTo("s1");
    }
}
