package io.github.sidneyroberto9.spring_session_lite.unit;

import io.github.sidneyroberto9.spring_session_lite.autoconfigure.SpringSessionLiteSseAutoConfiguration;
import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteIdleWatchTask;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers the scheduling wiring, which stopped being declarative in 2.3.0: the sweep used to carry
 * {@code @Scheduled(fixedDelay = 10_000)}, and now depends on a {@link ScheduledTaskRegistrar}
 * callback being registered as a bean. Nothing else fails if that bean silently goes missing —
 * idle-watch just never runs, on a green build — so it is asserted here directly.
 */
class SpringSessionLiteSseAutoConfigurationTest {

    private final SpringSessionLiteSseAutoConfiguration autoConfiguration = new SpringSessionLiteSseAutoConfiguration();
    private final SpringSessionLiteProperties properties = new SpringSessionLiteProperties();
    private final SpringSessionLiteIdleWatchTask task = mock(SpringSessionLiteIdleWatchTask.class);

    private List<IntervalTask> scheduledTasks(SpringSessionLiteProperties properties) {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        autoConfiguration.springSessionLiteIdleWatchScheduler(task, properties).configureTasks(registrar);

        return List.copyOf(registrar.getFixedDelayTaskList());
    }

    @Test
    void registersTheSweepAsAFixedDelayTaskAtTheConfiguredInterval() {
        properties.setIdleWatchInterval(Duration.ofSeconds(3));

        List<IntervalTask> tasks = scheduledTasks(properties);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getIntervalDuration()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void theScheduledTaskRunsTheSweep() {
        scheduledTasks(properties).get(0).getRunnable().run();

        verify(task).evaluate();
    }

    @Test
    void usesTenSecondsWhenTheIntervalIsNotConfigured() {
        assertThat(scheduledTasks(properties).get(0).getIntervalDuration()).isEqualTo(Duration.ofSeconds(10));
    }

    /**
     * Left unchecked, {@code 0s} surfaces as an {@code IllegalArgumentException} from deep inside
     * {@code ThreadPoolTaskScheduler} and an empty value as an NPE in the registrar — both opaque
     * context-refresh crashes that name neither the property nor the library.
     */
    @Test
    void rejectsANonPositiveIntervalWithAMessageNamingTheProperty() {
        for (Duration invalid : java.util.Arrays.asList(Duration.ZERO, Duration.ofSeconds(-1), null)) {
            properties.setIdleWatchInterval(invalid);

            assertThatThrownBy(() -> autoConfiguration.springSessionLiteIdleWatchScheduler(task, properties))
                    .as("interval: %s", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("idle-watch-interval");
        }
    }
}
