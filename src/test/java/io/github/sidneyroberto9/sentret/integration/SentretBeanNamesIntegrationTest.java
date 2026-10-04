package io.github.sidneyroberto9.sentret.integration;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Library beans carry a "sentret" prefix, so a host application's own bean with a generic name
 * (here "cookieManager") cannot collide with them.
 */
@SpringBootTest(classes = {SampleApplication.class, SentretBeanNamesIntegrationTest.HostBeans.class})
class SentretBeanNamesIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void hostBeanWithAGenericNameDoesNotClashWithTheLibrary() {
        assertThat(context.getBean("cookieManager")).isEqualTo("host application's own bean");
        assertThat(context.getBean(SentretCookieManager.class)).isNotNull();
    }

    @TestConfiguration
    static class HostBeans {

        @Bean
        String cookieManager() {
            return "host application's own bean";
        }
    }
}
