package io.github.sidneyroberto9.sentret.service;

import io.github.sidneyroberto9.sentret.security.SentretUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public class SentretUserService {

    public Optional<SentretUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.getPrincipal() instanceof SentretUser user) {
            return Optional.of(user);
        }

        return Optional.empty();
    }
}
