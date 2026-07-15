package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.store.SpringSessionLiteSessionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringSessionLiteSessionStoreTest {

    private static class MinimalSessionStore implements SpringSessionLiteSessionStore {

        @Override
        public SpringSessionLiteSession save(SpringSessionLiteSession session) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public Optional<SpringSessionLiteSession> findBySessionId(String sessionId) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void deleteBySessionId(String sessionId) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void deleteByUserId(String userId) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void deleteExpired(Instant now) {
            throw new UnsupportedOperationException("not needed for this test");
        }
    }

    @Test
    void findActiveDefaultMethodThrowsUnsupportedOperationExceptionWhenNotOverridden() {
        SpringSessionLiteSessionStore store = new MinimalSessionStore();
        Instant now = Instant.now();

        assertThatThrownBy(() -> store.findActive(now))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("findActive");
    }
}
