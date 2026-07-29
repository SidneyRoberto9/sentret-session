package io.github.sidneyroberto9.spring_session_lite.store;

import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSessionRepository;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;

@RequiredArgsConstructor
public class JpaSpringSessionLiteSessionStore implements SpringSessionLiteSessionStore {

    private final SpringSessionLiteSessionRepository repository;

    @Override
    public SpringSessionLiteSession save(SpringSessionLiteSession session) {
        return repository.save(session);
    }

    @Override
    public Optional<SpringSessionLiteSession> findBySessionId(String sessionId) {
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
