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

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteSseSessionEventListener springSessionLiteSseSessionEventListener(
            SessionEventBroadcaster broadcaster,
            SpringSessionLiteService sessionService
    ) {
        return new SpringSessionLiteSseSessionEventListener(broadcaster, sessionService);
    }
}
