package io.github.sidneyroberto9.spring_session_lite.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSessionRepository;
import io.github.sidneyroberto9.spring_session_lite.sample.SampleApplication;
import io.github.sidneyroberto9.spring_session_lite.sample.SampleController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for the opt-in SSE stack ({@code spring-session-lite.sse-enabled=true}):
 * the new {@code findByExpiresAtAfter} repository query backing
 * {@code SpringSessionLiteSessionStore#findActive}, and the
 * {@code SpringSessionLiteSseController}/security wiring for {@code GET /session/stream}. Full
 * event-streaming behavior (warning/logout/renew push) is covered at the component level in
 * {@code unit.SpringSessionLiteIdleWatchTaskTest} and
 * {@code unit.SpringSessionLiteSseSessionEventListenerTest} — MockMvc + real async SSE streaming
 * is fragile/verbose (see the task brief), so this class only exercises the parts that need a real
 * Spring context: the JPA query and the HTTP/security layer around the endpoint.
 */
@SpringBootTest(classes = SampleApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring-session-lite.sse-enabled=true",
        "spring-session-lite.endpoints-enabled=true"
})
class SpringSessionLiteSseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SpringSessionLiteSessionRepository sessionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDb() {
        sessionRepository.deleteAll();
    }

    private Cookie login(String userId, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SampleController.LoginRequest(userId, email, List.of()))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SLSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    // --- findByExpiresAtAfter (backs SpringSessionLiteSessionStore#findActive) ---

    @Test
    void findByExpiresAtAfterReturnsOnlyNotYetExpiredSessions() {
        Instant now = Instant.now();

        SpringSessionLiteSession active = new SpringSessionLiteSession();
        active.setSessionId("active-1");
        active.setUserId("user-active");
        active.setIpHash("hash");
        active.setCreatedAt(now);
        active.setLastAccessedAt(now);
        active.setExpiresAt(now.plus(1, ChronoUnit.HOURS));
        sessionRepository.save(active);

        SpringSessionLiteSession expired = new SpringSessionLiteSession();
        expired.setSessionId("expired-1");
        expired.setUserId("user-expired");
        expired.setIpHash("hash");
        expired.setCreatedAt(now.minus(2, ChronoUnit.HOURS));
        expired.setLastAccessedAt(now.minus(2, ChronoUnit.HOURS));
        expired.setExpiresAt(now.minus(1, ChronoUnit.HOURS));
        sessionRepository.save(expired);

        List<SpringSessionLiteSession> result = sessionRepository.findByExpiresAtAfter(now);

        assertThat(result).extracting(SpringSessionLiteSession::getSessionId).containsExactly("active-1");
    }

    // --- GET /session/stream ---

    @Test
    void streamWithoutCookieReturns401() throws Exception {
        // No change to permitAllPaths was needed for this endpoint (see the SSE autoconfiguration
        // javadoc) — the default security chain's anyRequest().authenticated() already covers it.
        mockMvc.perform(get("/session/stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void streamWithValidCookieStartsAsyncSseResponseWithAntiBufferingHeader() throws Exception {
        Cookie cookie = login("user1", "user1@test.com");

        // Content negotiation for the produces=TEXT_EVENT_STREAM_VALUE media type only finalizes
        // once the async request is dispatched/completed, which this test deliberately does not
        // do (a genuinely long-lived SSE connection never completes on its own — see the class
        // javadoc on why full async streaming isn't exercised here). asyncStarted() plus the
        // anti-buffering header are what's meaningfully verifiable at this stage.
        MvcResult result = mockMvc.perform(get("/session/stream").cookie(cookie))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
    }
}
