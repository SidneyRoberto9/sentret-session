package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.util.NanoId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class NanoIdTest {

    private static final Pattern URL_SAFE = Pattern.compile("^[A-Za-z0-9_-]+$");

    @Test
    void generatesRequestedLength() {
        assertThat(NanoId.generate(21)).hasSize(21);
        assertThat(NanoId.generate(8)).hasSize(8);
    }

    @Test
    void usesUrlSafeAlphabet() {
        for (int i = 0; i < 100; i++) {
            assertThat(NanoId.generate(32)).matches(URL_SAFE);
        }
    }

    @Test
    void producesPracticallyUniqueValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(NanoId.generate(21));
        }
        assertThat(seen).hasSize(10_000);
    }
}
