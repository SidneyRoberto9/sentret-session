package io.github.sidneyroberto9.sentret;

import io.github.sidneyroberto9.sentret.domain.SentretSessionRepository;
import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import io.github.sidneyroberto9.sentret.sample.SampleController;
import io.github.sidneyroberto9.sentret.security.SentretUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SentretApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SentretSessionRepository sessionRepository;

    @BeforeEach
    void cleanDb() {
        sessionRepository.deleteAll();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void contextLoads() {
    }

    @Test
    void loginWritesCookieWithCorrectAttributes() {
        ResponseEntity<SentretUser> response = rest.postForEntity(
                base() + "/login",
                new SampleController.LoginRequest("1", "test@test.com"),
                SentretUser.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String setCookie = response.getHeaders().getFirst("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("SENTRETSID=");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
        assertThat(setCookie).containsIgnoringCase("Max-Age=28800");
    }

    @Test
    void meWithValidCookieReturns200() {
        String cookie = doLogin("user1", "user1@test.com");

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", cookie);

        ResponseEntity<SentretUser> response = rest.exchange(
                base() + "/me", HttpMethod.GET, new HttpEntity<>(headers), SentretUser.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().userId()).isEqualTo("user1");
        assertThat(response.getBody().email()).isEqualTo("user1@test.com");
    }

    @Test
    void meWithNoCookieReturns401() {
        ResponseEntity<String> response = rest.getForEntity(base() + "/me", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginEndpointReachableWithoutSession() {
        ResponseEntity<SentretUser> response = rest.postForEntity(
                base() + "/login",
                new SampleController.LoginRequest("x", "x@x.com"),
                SentretUser.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void meWithTamperedCookieReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", "SENTRETSID=tampered-session-id-that-does-not-exist");

        ResponseEntity<String> response = rest.exchange(
                base() + "/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meWithExpiredSessionReturns401() {
        String cookie = doLogin("user2", "user2@test.com");
        String sessionId = sessionIdFrom(cookie);

        sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            s.setExpiresAt(Instant.now().minusSeconds(60));
            sessionRepository.save(s);
        });

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", cookie);

        ResponseEntity<String> response = rest.exchange(
                base() + "/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void cleanupRemovesExpiredSessions() {
        String cookie = doLogin("user3", "user3@test.com");
        String sessionId = sessionIdFrom(cookie);

        sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            s.setExpiresAt(Instant.now().minusSeconds(60));
            sessionRepository.save(s);
        });

        assertThat(sessionRepository.count()).isEqualTo(1);

        sessionRepository.deleteByExpiresAtBefore(Instant.now());

        assertThat(sessionRepository.count()).isEqualTo(0);
    }

    @Test
    void ipMismatchReturns401() {
        String cookie = doLogin("user4", "user4@test.com");
        String sessionId = sessionIdFrom(cookie);

        sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            s.setIpHash("0000000000000000000000000000000000000000000000000000000000000000");
            sessionRepository.save(s);
        });

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", cookie);

        ResponseEntity<String> response = rest.exchange(
                base() + "/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithDeadCookieStillReaches200() {
        // Regression for 1.3: a stale/expired cookie must NOT block re-login on a permit-all path.
        String cookie = doLogin("user5", "user5@test.com");
        String sessionId = sessionIdFrom(cookie);

        sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            s.setExpiresAt(Instant.now().minusSeconds(60));
            sessionRepository.save(s);
        });

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", cookie);

        ResponseEntity<SentretUser> response = rest.exchange(
                base() + "/login", HttpMethod.POST,
                new HttpEntity<>(new SampleController.LoginRequest("user5", "user5@test.com"), headers),
                SentretUser.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void logoutClearsCookieAndSession() {
        String cookie = doLogin("user6", "user6@test.com");
        String sessionId = sessionIdFrom(cookie);
        assertThat(sessionRepository.findBySessionId(sessionId)).isPresent();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", cookie);

        ResponseEntity<Void> logout = rest.exchange(
                base() + "/logout", HttpMethod.POST, new HttpEntity<>(headers), Void.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(logout.getHeaders().getFirst("Set-Cookie")).containsIgnoringCase("Max-Age=0");
        assertThat(sessionRepository.findBySessionId(sessionId)).isEmpty();
    }

    @Test
    void loginWithRolesExposesRolesInPrincipal() {
        SampleController.LoginRequest body = new SampleController.LoginRequest("user7", "user7@test.com", List.of("ADMIN", "USER"));
        ResponseEntity<SentretUser> login = rest.postForEntity(base() + "/login", body, SentretUser.class);
        String cookie = login.getHeaders().getFirst("Set-Cookie").split(";")[0];

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", cookie);

        ResponseEntity<SentretUser> me = rest.exchange(
                base() + "/me", HttpMethod.GET, new HttpEntity<>(headers), SentretUser.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().roles()).containsExactlyInAnyOrder("ADMIN", "USER");
    }

    private String doLogin(String userId, String email) {
        ResponseEntity<SentretUser> response = rest.postForEntity(
                base() + "/login",
                new SampleController.LoginRequest(userId, email),
                SentretUser.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = response.getHeaders().getFirst("Set-Cookie");
        assertThat(setCookie).isNotNull();
        return setCookie.split(";")[0];
    }

    private String sessionIdFrom(String cookie) {
        return cookie.split("=", 2)[1];
    }
}
