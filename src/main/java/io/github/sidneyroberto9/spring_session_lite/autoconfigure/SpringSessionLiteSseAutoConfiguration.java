package io.github.sidneyroberto9.spring_session_lite.autoconfigure;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.store.SpringSessionLiteSessionStore;
import io.github.sidneyroberto9.spring_session_lite.web.sse.InMemorySessionEventBroadcaster;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SessionEventBroadcaster;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteIdleWatchTask;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseController;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseRegistry;
import io.github.sidneyroberto9.spring_session_lite.web.sse.SpringSessionLiteSseSessionEventListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

import java.time.Duration;

/**
 * Registers the opt-in SSE push stack: {@code GET <endpoints-base-path>/stream}, the in-memory
 * emitter registry/broadcaster, the idle-watch sweep, and the listener bridging
 * {@code SessionDestroyedEvent}/{@code SessionRenewedEvent} to the broadcaster. Off by default
 * ({@code sse-enabled=false}) — existing consumers of the library are unaffected until they
 * explicitly turn this on. Independent of {@code endpoints-enabled}/{@code cleanup-enabled}: the
 * SSE stream and idle-watch sweep work whether or not the Task 2 REST controller or the periodic
 * cleanup task are enabled.
 *
 * <p>All beans are registered as explicit {@code @Bean}s, same reasoning as
 * {@link SpringSessionLiteEndpointsAutoConfiguration}: a consumer's {@code @ComponentScan} does
 * not reach into this library's package.
 *
 * <p>{@code @EnableScheduling} is declared here in addition to the one already inside
 * {@code SpringSessionLiteAutoConfiguration.CleanupConfiguration} (conditioned on
 * {@code cleanup-enabled}) — the idle-watch task must run regardless of whether the cleanup task
 * is enabled, and {@code @EnableScheduling} is safe to declare more than once in the same Spring
 * context.
 *
 * <p>No new property is added for the security permit-all path: {@code GET /session/stream}
 * requires authentication like any other path not listed in {@code permitAllPaths}, and no change
 * to {@code sessionLiteSecurityFilterChain()} was needed for that.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "spring-session-lite", name = "sse-enabled")
@EnableConfigurationProperties(SpringSessionLiteProperties.class)
@EnableScheduling
public class SpringSessionLiteSseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteSseRegistry springSessionLiteSseRegistry() {
        return new SpringSessionLiteSseRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(SessionEventBroadcaster.class)
    public SessionEventBroadcaster sessionEventBroadcaster(SpringSessionLiteSseRegistry registry) {
        return new InMemorySessionEventBroadcaster(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteSseController springSessionLiteSseController(SpringSessionLiteSseRegistry registry) {
        return new SpringSessionLiteSseController(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteIdleWatchTask springSessionLiteIdleWatchTask(
            SpringSessionLiteSessionStore store,
            SpringSessionLiteService sessionService,
            SpringSessionLiteProperties properties,
            SessionEventBroadcaster broadcaster
    ) {
        return new SpringSessionLiteIdleWatchTask(broadcaster, store, properties, sessionService);
    }

    /**
     * Schedules the sweep programmatically rather than with {@code @Scheduled(fixedDelayString)}.
     * That attribute resolves its placeholder to a raw String and accepts only a plain millisecond
     * count or an ISO-8601 duration — {@code "10s"} throws at context startup. Every other duration
     * in this namespace ({@code ttl=8h}, {@code max-idle=15m}) uses Boot's relaxed style, so the
     * interval is bound as a {@link java.time.Duration} on {@link SpringSessionLiteProperties} and
     * handed to the registrar here, where all three spellings work.
     *
     * <p>The interval is validated before it reaches the registrar: an empty value binds to
     * {@code null} and a zero/negative one is rejected by {@code scheduleWithFixedDelay}, both of
     * which surface as an opaque failure during context refresh rather than as something a consumer
     * can act on.
     *
     * @since 2.3.0
     */
    @Bean
    @ConditionalOnMissingBean(name = "springSessionLiteIdleWatchScheduler")
    public SchedulingConfigurer springSessionLiteIdleWatchScheduler(
            SpringSessionLiteIdleWatchTask task,
            SpringSessionLiteProperties properties
    ) {
        Duration interval = properties.getIdleWatchInterval();

        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalStateException("[spring-session-lite] 'idle-watch-interval' must be a positive duration (e.g. 10s, 500ms, PT10S); got: " + interval);
        }

        return registrar -> registrar.addFixedDelayTask(task::evaluate, interval);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteSseSessionEventListener springSessionLiteSseSessionEventListener(
            SessionEventBroadcaster broadcaster,
            SpringSessionLiteService sessionService
    ) {
        return new SpringSessionLiteSseSessionEventListener(broadcaster, sessionService);
    }
}
