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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hub as eleva-docs runs it: custom base path, idle enforcement on, login URL echoed. The
 * three endpoints the @media4all/session-lite client calls must keep their contract.
 */
@SpringBootTest(classes = SampleApplication.class)
@TestPropertySource(properties = {
        "sentret.hub.enabled=true",
        "sentret.hub.base-path=/api/lite/session",
        "sentret.hub.login-url=https://login.example.com",
        "sentret.max-idle=10m"
})
class SentretHubIntegrationTest {

    private static final String HUB = "/api/lite/session";
    private static final long TTL_MS = 8 * 60 * 60 * 1000L;
    private static final long MAX_IDLE_MS = 10 * 60 * 1000L;

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

    private Cookie login(String userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"email\":\"" + userId + "@test.com\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SENTRETSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private void setColumn(String column, String sessionId, Instant value) {
        jdbc.update("UPDATE sentret_sessions SET " + column + " = ? WHERE session_id = ?", value.toEpochMilli(), sessionId);
    }

    private Instant column(String column, String sessionId) {
        Long millis = jdbc.queryForObject("SELECT " + column + " FROM sentret_sessions WHERE session_id = ?", Long.class, sessionId);
        return Instant.ofEpochMilli(millis);
    }

    @Test
    void statusIsPermitAllAndAnonymousCarriesOnlyTheConfig() throws Exception {
        mockMvc.perform(get(HUB + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.absoluteRemainingMs").doesNotExist())
                .andExpect(jsonPath("$.config.heartbeatIntervalMs").value(60_000))
                .andExpect(jsonPath("$.config.statusPollIntervalMs").value(30_000))
                .andExpect(jsonPath("$.config.warningBeforeMs").value(60_000))
                .andExpect(jsonPath("$.config.loginUrl").value("https://login.example.com"))
                .andExpect(jsonPath("$.config.ttlMs").doesNotExist())
                .andExpect(jsonPath("$.config.maxIdleMs").doesNotExist())
                .andExpect(jsonPath("$.config.logoutUrl").doesNotExist())
                .andExpect(jsonPath("$.config.redirectAfterExpiryUrl").doesNotExist());
    }

    @Test
    void defaultBasePathIsNotExposed() throws Exception {
        mockMvc.perform(get("/session/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusWithValidCookieReturnsUserAndRemainingTimes() throws Exception {
        Cookie cookie = login("user1");

        mockMvc.perform(get(HUB + "/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("user1"))
                .andExpect(jsonPath("$.email").value("user1@test.com"))
                .andExpect(jsonPath("$.absoluteRemainingMs").value(greaterThan((int) (TTL_MS - 10_000))))
                .andExpect(jsonPath("$.idleRemainingMs").value(greaterThan((int) (MAX_IDLE_MS - 10_000))));
    }

    @Test
    void heartbeatResetsTheIdleClock() throws Exception {
        Cookie cookie = login("user2");
        setColumn("last_accessed_at", cookie.getValue(), Instant.now().minus(6, ChronoUnit.MINUTES));

        mockMvc.perform(post(HUB + "/heartbeat").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idleRemainingMs").value(greaterThan((int) (MAX_IDLE_MS - 10_000))));

        assertThat(column("last_accessed_at", cookie.getValue())).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void heartbeatWithoutCookieReturns401() throws Exception {
        mockMvc.perform(post(HUB + "/heartbeat")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusPollDoesNotAdvanceLastAccessedAt() throws Exception {
        Cookie cookie = login("user-poll");
        Instant stale = Instant.now().minus(6, ChronoUnit.MINUTES);
        setColumn("last_accessed_at", cookie.getValue(), stale);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get(HUB + "/status").cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true));
        }

        assertThat(column("last_accessed_at", cookie.getValue())).isCloseTo(stale, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void statusPollDoesNotRescueAnIdleExpiredSession() throws Exception {
        Cookie cookie = login("user-idle");
        setColumn("last_accessed_at", cookie.getValue(), Instant.now().minus(11, ChronoUnit.MINUTES));

        mockMvc.perform(get(HUB + "/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));

        mockMvc.perform(post(HUB + "/heartbeat").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void renewResetsAbsoluteExpiryAndRewritesTheCookie() throws Exception {
        Cookie cookie = login("user3");
        Instant nearExpiry = Instant.now().plusSeconds(60);
        setColumn("expires_at", cookie.getValue(), nearExpiry);

        mockMvc.perform(post(HUB + "/renew").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absoluteRemainingMs").value(greaterThan((int) (TTL_MS - 10_000))))
                .andExpect(header().exists("Set-Cookie"));

        assertThat(column("expires_at", cookie.getValue())).isAfter(nearExpiry);
    }

    @Test
    void renewWithoutCookieReturns401WithoutBody() throws Exception {
        mockMvc.perform(post(HUB + "/renew"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    /** The client never called it: every app logs out through its own endpoint. */
    @Test
    void logoutEndpointNoLongerExists() throws Exception {
        Cookie cookie = login("user4");

        // 404 (no handler) or 405 (static-resource fallback): either way, nothing answers it.
        mockMvc.perform(post(HUB + "/logout").cookie(cookie)).andExpect(status().is4xxClientError());

        mockMvc.perform(get(HUB + "/status").cookie(cookie)).andExpect(jsonPath("$.authenticated").value(true));
    }
}
