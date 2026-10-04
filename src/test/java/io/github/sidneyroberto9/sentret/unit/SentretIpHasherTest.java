package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.service.SentretIpHasher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SentretIpHasherTest {

    private SentretIpHasher hasher(String salt) {
        SentretProperties props = new SentretProperties();
        props.setIpHashSalt(salt);
        return new SentretIpHasher(props);
    }

    @Test
    void isDeterministicAndHex64() {
        SentretIpHasher hasher = hasher("secret");
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
        SentretIpHasher hasher = hasher("secret");
        String first = hasher.hash("");
        String second = hasher.hash("");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void nullIpWrapsFailureInIllegalStateException() {
        SentretIpHasher hasher = hasher("secret");

        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("IP hashing failed")
                .hasCauseInstanceOf(NullPointerException.class);
    }
}
