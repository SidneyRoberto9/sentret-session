package io.github.sidneyroberto9.sentret.integration;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real servlet container. MockMvc never performs the ERROR and ASYNC dispatches, so
 * only a real server shows whether errors and async responses keep their status instead of
 * turning into a 401. Uses the JDK HttpClient and the local.server.port property, which are the
 * same in Spring Boot 3 and 4.
 */
@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SentretRealServerIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String loginCookie() throws Exception {
        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"real-1\",\"email\":\"real-1@test.com\"}"))
                .build());
        assertThat(login.statusCode()).isEqualTo(200);
        return login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Cookie", cookie).GET().build());
    }

    @Test
    void authenticatedClientErrorKeepsItsStatus() throws Exception {
        assertThat(get("/boom", loginCookie()).statusCode()).isEqualTo(400);
    }

    @Test
    void authenticatedUnknownPathIsNotFound() throws Exception {
        assertThat(get("/does-not-exist", loginCookie()).statusCode()).isEqualTo(404);
    }

    @Test
    void authenticatedAsyncEndpointCompletes() throws Exception {
        HttpResponse<String> response = get("/async", loginCookie());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("done for real-1");
    }

    @Test
    void anonymousMalformedLoginIsABadRequest() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{not json"))
                .build());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void anonymousProtectedPathIsStillUnauthorized() throws Exception {
        assertThat(send(HttpRequest.newBuilder(uri("/me")).GET().build()).statusCode()).isEqualTo(401);
    }
}
