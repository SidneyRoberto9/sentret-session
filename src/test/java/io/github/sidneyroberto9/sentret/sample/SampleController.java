package io.github.sidneyroberto9.sentret.sample;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.web.SentretCurrentSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SampleController {

    private final SentretService sessionService;

    @PostMapping("/login")
    public ResponseEntity<SentretUser> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        SentretUser user = sessionService.login(body.userId(), body.email(), response);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionService.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<SentretUser> me(@SentretCurrentSession SentretUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(user);
    }

    public record LoginRequest(String userId, String email) {
    }
}
