package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.autoconfigure.SentretAutoConfiguration;
import io.github.sidneyroberto9.sentret.autoconfigure.SentretHubAutoConfiguration;
import io.github.sidneyroberto9.sentret.hub.SentretHubController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SentretHubAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SentretAutoConfiguration.class, SentretHubAutoConfiguration.class));

    /** Turning the library off must win over a leftover hub flag instead of failing the startup. */
    @Test
    void hubStaysOffWhenTheLibraryIsDisabled() {
        runner.withPropertyValues("sentret.enabled=false", "sentret.hub.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SentretHubController.class);
                });
    }
}
