package io.github.sidneyroberto9.sentret.integration;

import io.github.sidneyroberto9.sentret.sample.SampleApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The XSRF-TOKEN cookie must carry the session cookie's domain, SameSite and Secure, so a frontend
 * on a sibling subdomain can read it. Checked on a real container: Spring Security passes SameSite
 * as a Servlet 6 cookie attribute, which the container serializes and MockMvc does not.
 */
@SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "sentret.csrf-enabled=true",
        "sentret.cookie-domain=example.com",
        "sentret.cookie-same-site=None",
        "sentret.cookie-secure=true"
})
class SentretCsrfCookieRealServerTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void tokenCookieFollowsTheSessionCookieDomainSameSiteAndSecure() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/public/ping")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        String xsrf = response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow();

        assertThat(xsrf).contains("Domain=example.com").containsIgnoringCase("SameSite=None").contains("Secure");
    }
}
