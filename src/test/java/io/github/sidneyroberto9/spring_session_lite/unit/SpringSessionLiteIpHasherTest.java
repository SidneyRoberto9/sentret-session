package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteIpHasher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringSessionLiteIpHasherTest {

    private SpringSessionLiteIpHasher hasher(String salt) {
        SpringSessionLiteProperties props = new SpringSessionLiteProperties();
        props.setIpHashSalt(salt);
        return new SpringSessionLiteIpHasher(props);
    }

    @Test
    void isDeterministicAndHex64() {
        SpringSessionLiteIpHasher hasher = hasher("secret");
        String first = hasher.hash("192.168.0.1");
        String second = hasher.hash("192.168.0.1");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void differentSaltProducesDifferentHash() {
        assertThat(hasher("salt-a").hash("192.168.0.1"))
                .isNotEqualTo(hasher("salt-b").hash("192.168.0.1"));
    }

    @Test
    void emptyIpIsDeterministicAndHex64() {
        SpringSessionLiteIpHasher hasher = hasher("secret");
        String first = hasher.hash("");
        String second = hasher.hash("");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void nullIpWrapsFailureInIllegalStateException() {
        SpringSessionLiteIpHasher hasher = hasher("secret");

        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("IP hashing failed")
                .hasCauseInstanceOf(NullPointerException.class);
    }
}
