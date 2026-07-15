package io.github.sidneyroberto9.spring_session_lite.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for the opt-in {@code /session/*} endpoints
 * ({@link io.github.sidneyroberto9.spring_session_lite.web.controller.SpringSessionLiteSessionController}),
 * exercised over real HTTP via MockMvc (including the Spring Security filter chain), per the
 * plan's "Verificação" section for this task.
 */
@SpringBootTest(classes = SampleApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring-session-lite.endpoints-enabled=true",
        "spring-session-lite.max-idle=10m"
})
class SpringSessionLiteSessionControllerIntegrationTest {

    private static final long TTL_MS = 8 * 60 * 60 * 1000L; // default ttl=8h, unchanged by this test class
    private static final long MAX_IDLE_MS = 10 * 60 * 1000L; // overridden above

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

    private Cookie login(String userId, String email, List<String> roles) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SampleController.LoginRequest(userId, email, roles))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("SLSID");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private String sessionIdFrom(Cookie cookie) {
        return cookie.getValue();
    }

    // --- GET /session/status ---

    @Test
    void statusIsPermitAllAndAnonymousReturnsUnauthenticatedWithConfigEcho() throws Exception {
        mockMvc.perform(get("/session/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.absoluteRemainingMs").doesNotExist())
                .andExpect(jsonPath("$.idleRemainingMs").doesNotExist())
                .andExpect(jsonPath("$.config.ttlMs").value(TTL_MS))
                .andExpect(jsonPath("$.config.maxIdleMs").value(MAX_IDLE_MS))
                .andExpect(jsonPath("$.config.heartbeatIntervalMs").value(60_000))
                .andExpect(jsonPath("$.config.statusPollIntervalMs").value(30_000))
                .andExpect(jsonPath("$.config.warningBeforeMs").value(60_000));
    }

    @Test
    void statusWithValidCookieReturnsAuthenticatedUserAndRemainingMs() throws Exception {
        Cookie cookie = login("user1", "user1@test.com", List.of("ADMIN", "USER"));

        mockMvc.perform(get("/session/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("user1"))
                .andExpect(jsonPath("$.email").value("user1@test.com"))
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder("ADMIN", "USER")))
                .andExpect(jsonPath("$.absoluteRemainingMs").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.absoluteRemainingMs").value(org.hamcrest.Matchers.lessThanOrEqualTo((int) TTL_MS)))
                .andExpect(jsonPath("$.idleRemainingMs").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.config.ttlMs").value(TTL_MS));
    }

    @Test
    void statusWithNoCookieIsAnonymousNot401() throws Exception {
        // /session/status must stay reachable without a session (permit-all), unlike the other
        // /session/* endpoints below.
        mockMvc.perform(get("/session/status"))
                .andExpect(status().isOk());
    }

    // --- POST /session/heartbeat ---

    @Test
    void heartbeatAdvancesLastAccessedAtAndReturnsCurrentStatus() throws Exception {
        Cookie cookie = login("user2", "user2@test.com", List.of());
        String sessionId = sessionIdFrom(cookie);

        // Push lastAccessedAt beyond the effective throttle (min(5m default, maxIdle/2=5m) = 5m)
        // so the controller's touch() on the heartbeat request actually advances it, instead of
        // being throttled away.
        Instant staleLastAccessed = Instant.now().minus(6, ChronoUnit.MINUTES);
        sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            s.setLastAccessedAt(staleLastAccessed);
            sessionRepository.save(s);
        });

        mockMvc.perform(post("/session/heartbeat").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("user2"))
                .andExpect(jsonPath("$.idleRemainingMs").value(org.hamcrest.Matchers.greaterThan((int) (MAX_IDLE_MS - 10_000))));

        Instant advanced = sessionRepository.findBySessionId(sessionId).orElseThrow().getLastAccessedAt();
        assertThat(advanced).isAfter(staleLastAccessed.plusSeconds(5 * 60 - 5));
    }

    @Test
    void heartbeatWithoutCookieReturns401() throws Exception {
        mockMvc.perform(post("/session/heartbeat"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The client polls {@code GET /session/status} every {@code statusPollInterval} (30s by
     * default) whether or not the user is there. Over real HTTP — filter chain included — that poll
     * must leave {@code lastAccessedAt} untouched, or {@code maxIdle} is unreachable: with the
     * effective throttle capped at {@code maxIdle / 2}, a poll more frequent than that refreshes
     * the idle deadline forever and the session never expires.
     */
    @Test
    void statusPollDoesNotAdvanceLastAccessedAt() throws Exception {
        Cookie cookie = login("user-poll", "poll@test.com", List.of());
        String sessionId = sessionIdFrom(cookie);

        // Well past the effective throttle (5m): a touch here would definitely fire and be visible.
        Instant staleLastAccessed = Instant.now().minus(6, ChronoUnit.MINUTES);
        sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            s.setLastAccessedAt(staleLastAccessed);
            sessionRepository.save(s);
        });

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/session/status").cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true));
        }

        Instant after = sessionRepository.findBySessionId(sessionId).orElseThrow().getLastAccessedAt();
        assertThat(after).isCloseTo(staleLastAccessed, within(1, ChronoUnit.SECONDS));
    }

    /**
     * The observable end of the bug: an idle-expired session must not be resurrected by the poll
     * that is supposed to be watching it die.
     */
    @Test
    void statusPollDoesNotRescueIdleExpiredSession() throws Exception {
        Cookie cookie = login("user-idle", "idle@test.com", List.of());
        String sessionId = sessionIdFrom(cookie);

        // Idle for 11m against max-idle=10m: already past the window.
        sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            s.setLastAccessedAt(Instant.now().minus(11, ChronoUnit.MINUTES));
            sessionRepository.save(s);
        });

        mockMvc.perform(get("/session/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));

        mockMvc.perform(post("/session/heartbeat").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    // --- POST /session/renew ---

    @Test
    void renewResetsAbsoluteExpiryAndReturnsStatus() throws Exception {
        Cookie cookie = login("user3", "user3@test.com", List.of());
        String sessionId = sessionIdFrom(cookie);

        Instant nearExpiry = Instant.now().plusSeconds(60);
        sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            s.setExpiresAt(nearExpiry);
            sessionRepository.save(s);
        });

        mockMvc.perform(post("/session/renew").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.absoluteRemainingMs").value(org.hamcrest.Matchers.greaterThan((int) (TTL_MS - 10_000))))
                .andExpect(header().exists("Set-Cookie"));

        Instant renewedExpiresAt = sessionRepository.findBySessionId(sessionId).orElseThrow().getExpiresAt();
        assertThat(renewedExpiresAt).isAfter(nearExpiry);
    }

    @Test
    void renewWithoutValidCookieReturns401WithStandardErrorBody() throws Exception {
        mockMvc.perform(post("/session/renew"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    // --- POST /session/logout ---

    @Test
    void logoutClearsSessionThenNextRequestReturns401() throws Exception {
        Cookie cookie = login("user4", "user4@test.com", List.of());
        String sessionId = sessionIdFrom(cookie);
        assertThat(sessionRepository.findBySessionId(sessionId)).isPresent();

        mockMvc.perform(post("/session/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        assertThat(sessionRepository.findBySessionId(sessionId)).isEmpty();

        // Same (now dead) cookie must no longer authenticate.
        mockMvc.perform(post("/session/heartbeat").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }
}
