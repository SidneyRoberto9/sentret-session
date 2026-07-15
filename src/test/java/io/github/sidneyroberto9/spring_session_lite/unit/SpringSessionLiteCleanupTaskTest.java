package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.scheduler.SpringSessionLiteCleanupTask;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class SpringSessionLiteCleanupTaskTest {

    @Test
    void deleteExpiredDelegatesToSessionService() {
        SpringSessionLiteService sessionService = mock(SpringSessionLiteService.class);
        SpringSessionLiteCleanupTask cleanupTask = new SpringSessionLiteCleanupTask(sessionService);

        cleanupTask.deleteExpired();

        verify(sessionService, times(1)).deleteExpired();
        verifyNoMoreInteractions(sessionService);
    }
}
