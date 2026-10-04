package io.github.sidneyroberto9.sentret.security;

import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class SentretAuthenticationFilter extends OncePerRequestFilter {

    private final SentretService sessionService;
    private final SentretCookieManager cookieManager;
    private final SecurityContextRepository contextRepository = new RequestAttributeSecurityContextRepository();

    public SentretAuthenticationFilter(SentretService sessionService, SentretCookieManager cookieManager) {
        this.sessionService = sessionService;
        this.cookieManager = cookieManager;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String sessionId = cookieManager.read(request);

        if (sessionId == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<SentretUser> user = sessionService.validate(sessionId);

        if (user.isEmpty()) {
            // Invalid/expired cookie: do NOT short-circuit with 401 here — that would also block
            // permit-all paths (e.g. re-login). Drop the dead cookie, stay anonymous, and let
            // authorization + the AuthenticationEntryPoint decide the response.
            SecurityContextHolder.clearContext();
            cookieManager.clear(response);
            chain.doFilter(request, response);
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user.get(), null, List.of()));
        SecurityContextHolder.setContext(context);
        // Saved on the request so the ERROR and ASYNC dispatches of this same request stay
        // authenticated: a stateless chain starts each dispatch from an empty context otherwise,
        // and every error or async response would turn into a 401.
        contextRepository.saveContext(context, request, response);
        chain.doFilter(request, response);
    }
}
