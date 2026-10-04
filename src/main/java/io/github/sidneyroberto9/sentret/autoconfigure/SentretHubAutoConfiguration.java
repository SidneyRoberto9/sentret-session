package io.github.sidneyroberto9.sentret.autoconfigure;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.hub.SentretHubController;
import io.github.sidneyroberto9.sentret.hub.SentretHubStatusService;
import io.github.sidneyroberto9.sentret.service.SentretService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the opt-in inactivity hub ({@code sentret.hub.enabled=true}). Explicit beans because a
 * consumer's component scan does not reach this package. The permit-all rule for {@code status}
 * lives in {@link SentretAutoConfiguration}, the one place that builds the default chain. Stays off
 * when the library itself is disabled ({@code sentret.enabled=false}).
 */
@AutoConfiguration(after = SentretAutoConfiguration.class)
@ConditionalOnBean(SentretService.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "sentret.hub", name = "enabled")
@EnableConfigurationProperties(SentretProperties.class)
public class SentretHubAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SentretHubStatusService sentretHubStatusService(SentretProperties properties) {
        return new SentretHubStatusService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretHubController sentretHubController(SentretService sentretService, SentretHubStatusService sentretHubStatusService) {
        return new SentretHubController(sentretService, sentretHubStatusService);
    }
}
