package io.github.sidneyroberto9.sentret.integration;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Coverage for the two opt-in branches of the default security chain: {@code csrf-enabled=true} and
 * CORS, which turns on by itself when {@code cors-allowed-origins} is not empty. The default side of
 * both (no CSRF, no CORS) is covered by {@link io.github.sidneyroberto9.sentret.SentretApplicationTests}.
 */
@SpringBootTest(classes = SampleApplication.class)
@TestPropertySource(properties = {
        "sentret.csrf-enabled=true",
        "sentret.cors-allowed-origins=http://example.com"
})
class SentretAutoConfigurationIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void csrfEnabledRejectsStateChangingRequestWithoutToken() throws Exception {
        // /login is permit-all for authorization purposes, but CsrfFilter runs ahead of
        // authorization and rejects any POST lacking a valid CSRF token once csrf-enabled=true.
        mockMvc.perform(post("/login"))
                .andExpect(status().isForbidden());
    }

    @Test
    void corsEnabledAppliesConfiguredAllowedOriginOnPreflight() throws Exception {
        mockMvc.perform(options("/login")
                        .header("Origin", "http://example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://example.com"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void corsEnabledRejectsDisallowedOriginOnPreflight() throws Exception {
        mockMvc.perform(options("/login")
                        .header("Origin", "http://not-allowed.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
