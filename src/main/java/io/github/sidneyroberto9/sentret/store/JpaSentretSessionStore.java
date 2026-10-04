package io.github.sidneyroberto9.sentret.store;

import io.github.sidneyroberto9.sentret.domain.SentretSession;
import io.github.sidneyroberto9.sentret.domain.SentretSessionRepository;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;

@RequiredArgsConstructor
public class JpaSentretSessionStore implements SentretSessionStore {

    private final SentretSessionRepository repository;

    @Override
    public SentretSession save(SentretSession session) {
        return repository.save(session);
    }

    @Override
    public Optional<SentretSession> findBySessionId(String sessionId) {
        return repository.findBySessionId(sessionId);
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        repository.deleteBySessionId(sessionId);
    }

    @Override
    public void deleteByUserId(String userId) {
        repository.deleteByUserId(userId);
    }

    @Override
    public void deleteExpired(Instant now) {
        repository.deleteByExpiresAtBefore(now);
    }
}
