package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.scheduler.SentretCleanupTask;
import io.github.sidneyroberto9.sentret.service.SentretService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class SentretCleanupTaskTest {

    @Test
    void deleteExpiredDelegatesToSessionService() {
        SentretService sessionService = mock(SentretService.class);
        SentretCleanupTask cleanupTask = new SentretCleanupTask(sessionService);

        cleanupTask.deleteExpired();

        verify(sessionService, times(1)).deleteExpired();
        verifyNoMoreInteractions(sessionService);
    }
}
