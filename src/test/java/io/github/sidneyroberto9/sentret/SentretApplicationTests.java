package io.github.sidneyroberto9.sentret;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end behaviour through the real security filter chain. Uses only test APIs that are the
 * same in Spring Boot 3 and 4 (no TestRestTemplate, no @AutoConfigureMockMvc).
 */
@SpringBootTest(classes = SampleApplication.class)
class SentretApplicationTests {

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

    private Cookie login(String userId, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SENTRETSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private void expire(String sessionId) {
        jdbc.update("UPDATE sentret_sessions SET expires_at = ? WHERE session_id = ?",
                Instant.now().minusSeconds(60).toEpochMilli(), sessionId);
    }

    private int sessionCount(String sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sentret_sessions WHERE session_id = ?", Integer.class, sessionId);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void loginWritesCookieWithCorrectAttributes() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"1\",\"email\":\"test@test.com\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("SENTRETSID=");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
        assertThat(setCookie).containsIgnoringCase("Max-Age=28800");
    }

    @Test
    void meWithValidCookieReturns200() throws Exception {
        Cookie cookie = login("user1", "user1@test.com");

        mockMvc.perform(get("/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user1"))
                .andExpect(jsonPath("$.email").value("user1@test.com"))
                .andExpect(jsonPath("$.roles").doesNotExist());
    }

    @Test
    void meWithNoCookieReturns401WithoutBody() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void loginEndpointReachableWithoutSession() throws Exception {
        login("x", "x@x.com");
    }

    /** A forged or oversized cookie must end in a clean 401, never in a database error. */
    @Test
    void meWithTamperedCookieReturns401AndClearsCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/me").cookie(new Cookie("SENTRETSID", "x".repeat(100))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void meWithExpiredSessionReturns401() throws Exception {
        Cookie cookie = login("user2", "user2@test.com");
        expire(cookie.getValue());

        mockMvc.perform(get("/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginPurgesExpiredSessions() throws Exception {
        Cookie old = login("user3", "user3@test.com");
        expire(old.getValue());

        login("user3b", "user3b@test.com");

        assertThat(sessionCount(old.getValue())).isZero();
    }

    /**
     * Without the hub nothing sends heartbeats, so max-idle cannot be measured: enforcing it would
     * log every active user out max-idle after login. Only the absolute ttl applies.
     */
    @Test
    void sessionWithoutTheHubIsNotCutByMaxIdle() throws Exception {
        Cookie cookie = login("user8", "user8@test.com");
        jdbc.update("UPDATE sentret_sessions SET last_accessed_at = ? WHERE session_id = ?",
                Instant.now().minusSeconds(31 * 60).toEpochMilli(), cookie.getValue());

        mockMvc.perform(get("/me").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void corsIsOffWhenNoOriginIsConfigured() throws Exception {
        mockMvc.perform(options("/login")
                        .header("Origin", "http://example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /**
     * The library used to put @EnableScheduling on the host application as a side effect. It must
     * not: scheduling is the host application's decision.
     */
    @Test
    void libraryDoesNotEnableSchedulingInTheHostApplication() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    /**
     * Mobile, VPN and corporate networks change the client IP mid-session. The session is bound to
     * the cookie only, so a new IP must not log the user out.
     */
    @Test
    void sessionSurvivesClientIpChange() throws Exception {
        Cookie cookie = login("user4", "user4@test.com");

        mockMvc.perform(get("/me").cookie(cookie).with(request -> {
                    request.setRemoteAddr("198.51.100.77");
                    return request;
                }))
                .andExpect(status().isOk());
    }

    @Test
    void loginWithDeadCookieStillReaches200() throws Exception {
        // A stale/expired cookie must NOT block re-login on a permit-all path.
        Cookie cookie = login("user5", "user5@test.com");
        expire(cookie.getValue());

        mockMvc.perform(post("/login").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user5\",\"email\":\"user5@test.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutClearsCookieAndSession() throws Exception {
        Cookie cookie = login("user6", "user6@test.com");
        assertThat(sessionCount(cookie.getValue())).isOne();

        MvcResult result = mockMvc.perform(post("/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).containsIgnoringCase("Max-Age=0");
        assertThat(sessionCount(cookie.getValue())).isZero();
    }
}
