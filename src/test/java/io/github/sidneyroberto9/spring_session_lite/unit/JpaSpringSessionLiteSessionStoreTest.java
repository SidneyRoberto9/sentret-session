package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSessionRepository;
import io.github.sidneyroberto9.spring_session_lite.store.JpaSpringSessionLiteSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaSpringSessionLiteSessionStoreTest {

    private SpringSessionLiteSessionRepository repository;
    private JpaSpringSessionLiteSessionStore store;

    @BeforeEach
    void setUp() {
        repository = mock(SpringSessionLiteSessionRepository.class);
        store = new JpaSpringSessionLiteSessionStore(repository);
    }

    private SpringSessionLiteSession session() {
        SpringSessionLiteSession session = new SpringSessionLiteSession();
        session.setSessionId("sid");
        session.setUserId("user-1");
        return session;
    }

    @Test
    void saveDelegatesToRepositoryAndReturnsItsResult() {
        SpringSessionLiteSession session = session();
        SpringSessionLiteSession saved = session();
        when(repository.save(session)).thenReturn(saved);

        SpringSessionLiteSession result = store.save(session);

        assertThat(result).isSameAs(saved);
        verify(repository).save(session);
    }

    @Test
    void findBySessionIdDelegatesToRepositoryAndReturnsItsResult() {
        SpringSessionLiteSession session = session();
        when(repository.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SpringSessionLiteSession> result = store.findBySessionId("sid");

        assertThat(result).contains(session);
        verify(repository).findBySessionId("sid");
    }

    @Test
    void deleteBySessionIdDelegatesToRepository() {
        store.deleteBySessionId("sid");

        verify(repository).deleteBySessionId("sid");
    }

    @Test
    void deleteByUserIdDelegatesToRepository() {
        store.deleteByUserId("user-1");

        verify(repository).deleteByUserId("user-1");
    }

    @Test
    void deleteExpiredDelegatesToRepositoryWithCutoffInstant() {
        Instant now = Instant.now();

        store.deleteExpired(now);

        verify(repository).deleteByExpiresAtBefore(now);
    }

    @Test
    void findActiveDelegatesToRepositoryAndReturnsItsResult() {
        Instant now = Instant.now();
        List<SpringSessionLiteSession> active = List.of(session());
        when(repository.findByExpiresAtAfter(now)).thenReturn(active);

        List<SpringSessionLiteSession> result = store.findActive(now);

        assertThat(result).isSameAs(active);
        verify(repository).findByExpiresAtAfter(now);
    }
}
