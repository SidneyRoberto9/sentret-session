package io.github.sidneyroberto9.sentret.integration;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import io.github.sidneyroberto9.sentret.security.SentretAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The setup every m4all application uses: its own SecurityFilterChain, written as in
 * docs/01 §6.2, plus the hub. Runs on a real container so error and async dispatches are real.
 */
@SpringBootTest(
        classes = {SampleApplication.class, SentretCustomChainRealServerTest.HostSecurity.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "sentret.hub.enabled=true",
        "sentret.hub.base-path=/api/lite/session",
        "sentret.max-idle=10m"
})
class SentretCustomChainRealServerTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private HttpResponse<String> send(String method, String path, String cookie, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));

        if (cookie != null) {
            builder.header("Cookie", cookie);
        }

        if (json != null) {
            builder.header("Content-Type", "application/json");
        }

        HttpRequest.BodyPublisher body = json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json);
        return http.send(builder.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login() throws Exception {
        HttpResponse<String> login = send("POST", "/login", null, "{\"userId\":\"chain-1\",\"email\":\"chain-1@test.com\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        return login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    @Test
    void sessionAuthenticatesThroughTheHostChain() throws Exception {
        String cookie = login();

        assertThat(send("GET", "/me", cookie, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/me", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void errorsKeepTheirStatusForLoggedInAndAnonymousUsers() throws Exception {
        String cookie = login();

        assertThat(send("GET", "/boom", cookie, null).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/login", null, "{not json").statusCode()).isEqualTo(400);
    }

    /** An async response is dispatched again; the user saved on the request keeps it authenticated. */
    @Test
    void asyncEndpointCompletesForALoggedInUser() throws Exception {
        HttpResponse<String> response = send("GET", "/async", login(), null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("done for chain-1");
    }

    @Test
    void hubWorksBehindTheHostChain() throws Exception {
        String cookie = login();

        assertThat(send("GET", "/api/lite/session/status", null, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/lite/session/heartbeat", cookie, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/lite/session/renew", cookie, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/lite/session/heartbeat", null, null).statusCode()).isEqualTo(401);
    }

    /** Mirrors the custom chain documented in docs/01 §6.2, with the hub status released. */
    @TestConfiguration
    static class HostSecurity {

        @Bean
        SecurityFilterChain hostChain(HttpSecurity http, SentretAuthenticationFilter sentretAuthenticationFilter) throws Exception {
            http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .authorizeHttpRequests(auth -> auth
                            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers("/login", "/api/lite/session/status").permitAll()
                            .anyRequest().authenticated())
                    .addFilterBefore(sentretAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        FilterRegistrationBean<SentretAuthenticationFilter> sentretFilterRegistration(SentretAuthenticationFilter filter) {
            FilterRegistrationBean<SentretAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }
    }
}
