package io.github.sidneyroberto9.spring_session_lite.integration;

import io.github.sidneyroberto9.spring_session_lite.sample.SampleApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Coverage for the two opt-in branches of
 * {@link io.github.sidneyroberto9.spring_session_lite.autoconfigure.SpringSessionLiteAutoConfiguration#sessionLiteSecurityFilterChain}
 * that the rest of the suite never exercises: {@code csrf-enabled=true} and
 * {@code cors-enabled=true} (including the private {@code corsConfigurationSource} helper). The
 * {@code false}/default side of both branches, the {@code endpoints-enabled} permit-all branch,
 * and the {@link org.springframework.security.web.AuthenticationEntryPoint} 401 JSON body are
 * already covered by {@link SpringSessionLiteSessionControllerIntegrationTest} and
 * {@link io.github.sidneyroberto9.spring_session_lite.SpringSessionLiteApplicationTests}, which
 * both boot with the library defaults ({@code csrf-enabled=false}, {@code cors-enabled=false}).
 */
@SpringBootTest(classes = SampleApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring-session-lite.csrf-enabled=true",
        "spring-session-lite.cors-enabled=true",
        "spring-session-lite.cors-allowed-origins=http://example.com"
})
class SpringSessionLiteAutoConfigurationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
