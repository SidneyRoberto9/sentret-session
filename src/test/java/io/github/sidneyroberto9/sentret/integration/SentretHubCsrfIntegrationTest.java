package io.github.sidneyroberto9.sentret.integration;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hub with csrf-enabled, as a cross-subdomain deployment runs it. The npm client sends heartbeat
 * and renew without an X-XSRF-TOKEN header, so those must not be blocked; the token cookie must
 * follow the session cookie's domain and SameSite so the frontend can read and return it.
 */
@SpringBootTest(classes = SampleApplication.class)
@TestPropertySource(properties = {
        "sentret.hub.enabled=true",
        "sentret.hub.base-path=/api/lite/session",
        "sentret.csrf-enabled=true",
        "sentret.cookie-domain=example.com",
        "sentret.cookie-same-site=None",
        "sentret.cookie-secure=true"
})
class SentretHubCsrfIntegrationTest {

    private static final String HUB = "/api/lite/session";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM sentret_sessions");
    }

    private MvcResult anyRequest() throws Exception {
        return mockMvc.perform(get("/public/ping").secure(true)).andReturn();
    }

    private Cookie login() throws Exception {
        Cookie xsrf = anyRequest().getResponse().getCookie("XSRF-TOKEN");

        MvcResult result = mockMvc.perform(post("/login").secure(true)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"csrf-1\",\"email\":\"csrf-1@test.com\"}"))
                .andExpect(status().isOk())
                .andReturn();

        return result.getResponse().getCookie("SENTRETSID");
    }

    @Test
    void heartbeatAndRenewWorkWithoutACsrfHeaderAsTheClientSendsThem() throws Exception {
        Cookie session = login();

        mockMvc.perform(post(HUB + "/heartbeat").secure(true).cookie(session)).andExpect(status().isOk());
        mockMvc.perform(post(HUB + "/renew").secure(true).cookie(session)).andExpect(status().isOk());
    }

    @Test
    void otherStateChangingRequestsStillNeedTheToken() throws Exception {
        Cookie session = login();

        mockMvc.perform(post("/logout").secure(true).cookie(session)).andExpect(status().isForbidden());
    }

    @Test
    void statusOmitsALoginUrlThatIsNotConfigured() throws Exception {
        mockMvc.perform(get(HUB + "/status").secure(true))
                .andExpect(status().isOk())
                // jsonPath().doesNotExist() also passes for an explicit null, so check the raw body.
                .andExpect(content().string(not(containsString("loginUrl"))));
    }
}
