package io.github.sidneyroberto9.sentret.unit;

import io.github.sidneyroberto9.sentret.autoconfigure.SentretAutoConfiguration;
import io.github.sidneyroberto9.sentret.service.SentretService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SentretAutoConfigurationTest {

    /** The library is servlet-only: a non-web context with it on the classpath must still start. */
    @Test
    void staysOffOutsideAServletWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SentretAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SentretService.class);
                });
    }
}
