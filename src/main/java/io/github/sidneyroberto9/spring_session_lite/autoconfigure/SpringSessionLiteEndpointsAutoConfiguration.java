package io.github.sidneyroberto9.spring_session_lite.autoconfigure;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.web.controller.SpringSessionLiteSessionController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the opt-in {@code /session/*} REST controller. Off by default
 * ({@code endpoints-enabled=false}) — existing consumers of the library are unaffected until they
 * explicitly turn this on. The controller is registered as an explicit {@code @Bean} because a
 * consumer's {@code @ComponentScan} does not reach into this library's package.
 *
 * <p>The permit-all wiring for {@code /session/status} lives in the already-existing
 * {@code sessionLiteSecurityFilterChain()} method in {@link SpringSessionLiteAutoConfiguration},
 * not here — mutating {@code permitAllPaths} from a second, separately-ordered
 * {@code @AutoConfiguration} would depend on bean-instantiation order between two distinct
 * autoconfigurations, which {@code @AutoConfiguration(before=...)}/{@code after=...} does not
 * guarantee.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "spring-session-lite", name = "endpoints-enabled")
@EnableConfigurationProperties(SpringSessionLiteProperties.class)
public class SpringSessionLiteEndpointsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteSessionController springSessionLiteSessionController(
            SpringSessionLiteService springSessionLiteService,
            SpringSessionLiteProperties properties
    ) {
        return new SpringSessionLiteSessionController(springSessionLiteService, properties);
    }
}
