package io.github.sidneyroberto9.sentret;

import io.github.sidneyroberto9.sentret.domain.SentretSessionRepository;
import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    private SentretSessionRepository sessionRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        sessionRepository.deleteAll();
    }

    private Cookie login(String userId, String email) throws Exception {
        return loginWithBody("{\"userId\":\"" + userId + "\",\"email\":\"" + email + "\"}");
    }

    private Cookie loginWithBody(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SENTRETSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private void expire(String sessionId) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setExpiresAt(Instant.now().minusSeconds(60));
            sessionRepository.save(session);
        });
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

    @Test
    void meWithTamperedCookieReturns401() throws Exception {
        mockMvc.perform(get("/me").cookie(new Cookie("SENTRETSID", "tampered-session-id-that-does-not-exist")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithExpiredSessionReturns401() throws Exception {
        Cookie cookie = login("user2", "user2@test.com");
        expire(cookie.getValue());

        mockMvc.perform(get("/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void cleanupRemovesExpiredSessions() throws Exception {
        Cookie cookie = login("user3", "user3@test.com");
        expire(cookie.getValue());
        assertThat(sessionRepository.count()).isEqualTo(1);

        sessionRepository.deleteByExpiresAtBefore(Instant.now());

        assertThat(sessionRepository.count()).isZero();
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
        assertThat(sessionRepository.findBySessionId(cookie.getValue())).isPresent();

        MvcResult result = mockMvc.perform(post("/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).containsIgnoringCase("Max-Age=0");
        assertThat(sessionRepository.findBySessionId(cookie.getValue())).isEmpty();
    }

}
