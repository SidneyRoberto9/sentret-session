package io.github.sidneyroberto9.sentret.sample;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import io.github.sidneyroberto9.sentret.service.SentretService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.Callable;

@RestController
@RequiredArgsConstructor
public class SampleController {

    private final SentretService sessionService;

    @PostMapping("/login")
    public ResponseEntity<SentretUser> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        SentretUser user = sessionService.login(body.userId(), body.email(), response);
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionService.logout(request, response);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /** Requires a session (not permit-all), so the security chain answers 401 before this runs. */
    @GetMapping("/me")
    public ResponseEntity<SentretUser> me(@AuthenticationPrincipal SentretUser user) {
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }

    /** An authenticated endpoint that fails with a client error, to exercise the ERROR dispatch. */
    @GetMapping("/boom")
    public ResponseEntity<Void> boom() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad input");
    }

    /** An authenticated async endpoint, to exercise the ASYNC dispatch. */
    @GetMapping("/async")
    public Callable<String> async(@AuthenticationPrincipal SentretUser user) {
        return () -> "done for " + user.userId();
    }

    public record LoginRequest(String userId, String email) {
    }
}
