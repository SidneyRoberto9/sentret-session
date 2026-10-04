package io.github.sidneyroberto9.sentret.scheduler;

import io.github.sidneyroberto9.sentret.service.SentretService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@RequiredArgsConstructor
public class SentretCleanupTask {

    private final SentretService sessionService;

    @Scheduled(cron = "${sentret.cleanup-cron:0 */30 * * * *}")
    public void deleteExpired() {
        sessionService.deleteExpired();
        log.info("Expired sessions cleanup executed");
    }
}
