package io.github.sidneyroberto9.sentret.integration;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        "sentret.csrf-ignored-paths=/logout",
        "sentret.cors-allowed-origins=http://example.com,https://*.example.org"
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

    /**
     * The flow a SPA uses with csrf-enabled: any response hands out the XSRF-TOKEN cookie, and the
     * raw cookie value sent back in the X-XSRF-TOKEN header is accepted.
     */
    @Test
    void csrfEnabledLetsASpaReadTheTokenCookieAndSendItBack() throws Exception {
        MvcResult first = mockMvc.perform(get("/public/ping")).andReturn();
        Cookie xsrf = first.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();

        mockMvc.perform(post("/login")
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"email\":\"u1@test.com\"}"))
                .andExpect(status().isOk());
    }

    /**
     * The npm client ends a session by POSTing to the app's logout URL without a CSRF header; an
     * app can exempt that path, and nothing else, through csrf-ignored-paths.
     */
    @Test
    void csrfIgnoredPathAcceptsARequestWithoutTheToken() throws Exception {
        MvcResult first = mockMvc.perform(get("/public/ping")).andReturn();
        Cookie xsrf = first.getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mockMvc.perform(post("/login")
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u2\",\"email\":\"u2@test.com\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = login.getResponse().getCookie("SENTRETSID");

        mockMvc.perform(post("/logout").cookie(session)).andExpect(status().isNoContent());
        mockMvc.perform(get("/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    /** Origin patterns such as https://*.example.org must work alongside credentials. */
    @Test
    void corsAcceptsAnOriginPattern() throws Exception {
        mockMvc.perform(options("/login")
                        .header("Origin", "https://app.example.org")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.org"));
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
