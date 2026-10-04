package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.domain.SentretSession;
import io.github.sidneyroberto9.sentret.domain.SentretSessionRepository;
import io.github.sidneyroberto9.sentret.store.JpaSentretSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaSentretSessionStoreTest {

    private SentretSessionRepository repository;
    private JpaSentretSessionStore store;

    @BeforeEach
    void setUp() {
        repository = mock(SentretSessionRepository.class);
        store = new JpaSentretSessionStore(repository);
    }

    private SentretSession session() {
        SentretSession session = new SentretSession();
        session.setSessionId("sid");
        session.setUserId("user-1");
        return session;
    }

    @Test
    void saveDelegatesToRepositoryAndReturnsItsResult() {
        SentretSession session = session();
        SentretSession saved = session();
        when(repository.save(session)).thenReturn(saved);

        SentretSession result = store.save(session);

        assertThat(result).isSameAs(saved);
        verify(repository).save(session);
    }

    @Test
    void findBySessionIdDelegatesToRepositoryAndReturnsItsResult() {
        SentretSession session = session();
        when(repository.findBySessionId("sid")).thenReturn(Optional.of(session));

        Optional<SentretSession> result = store.findBySessionId("sid");

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

}
