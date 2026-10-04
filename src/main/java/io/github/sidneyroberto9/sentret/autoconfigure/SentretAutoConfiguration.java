package io.github.sidneyroberto9.sentret.autoconfigure;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.config.SentretSecurityValidator;
import io.github.sidneyroberto9.sentret.security.SentretAuthenticationFilter;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.service.SentretUserService;
import io.github.sidneyroberto9.sentret.store.JdbcSentretSessionStore;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the library's beans and, unless the application declares its own, the default
 * SecurityFilterChain. Ordered ahead of Boot's own default chains (named, not referenced as classes,
 * so the same jar runs on Boot 3 and Boot 4; names absent from the classpath are ignored).
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({JdbcTemplate.class, SecurityFilterChain.class})
@ConditionalOnProperty(prefix = "sentret", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SentretProperties.class)
public class SentretAutoConfiguration {

    private static final List<String> CORS_ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    @Bean
    @ConditionalOnMissingBean
    public SentretSecurityValidator sentretSecurityValidator(SentretProperties properties) {
        return new SentretSecurityValidator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretCookieManager sentretCookieManager(SentretProperties properties) {
        return new SentretCookieManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretSessionStore sentretSessionStore(JdbcTemplate jdbcTemplate) {
        return new JdbcSentretSessionStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretService sentretService(
            SentretProperties properties,
            SentretSessionStore store,
            SentretCookieManager sentretCookieManager,
            ApplicationEventPublisher eventPublisher
    ) {
        return new SentretService(store, properties, eventPublisher, sentretCookieManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretUserService sentretUserService() {
        return new SentretUserService();
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretAuthenticationFilter sentretAuthenticationFilter(
            SentretService sentretService,
            SentretCookieManager sentretCookieManager
    ) {
        return new SentretAuthenticationFilter(sentretService, sentretCookieManager);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain sentretSecurityFilterChain(
            HttpSecurity http,
            SentretAuthenticationFilter sentretAuthenticationFilter,
            SentretProperties properties
    ) throws Exception {
        List<String> permitAll = new ArrayList<>(properties.getPermitAllPaths());

        if (properties.getHub().isEnabled()) {
            permitAll.add(properties.getHub().getBasePath() + "/status");
        }

        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .logout(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // The error page renders the status of a request that was already
                        // authorized (or rejected) on its first dispatch.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(permitAll.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(sentretAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (properties.isCsrfEnabled()) {
            http.csrf(csrf -> csrf
                    .csrfTokenRepository(csrfTokenRepository(properties))
                    .csrfTokenRequestHandler(spaCsrfTokenRequestHandler())
                    .ignoringRequestMatchers(csrfExemptPaths(properties)));
        } else {
            http.csrf(AbstractHttpConfigurer::disable);
        }

        if (properties.getCorsAllowedOrigins().isEmpty()) {
            http.cors(AbstractHttpConfigurer::disable);
        } else {
            http.cors(cors -> cors.configurationSource(corsConfigurationSource(properties)));
        }

        return http.build();
    }

    /**
     * The XSRF-TOKEN cookie follows the session cookie's domain, SameSite and Secure, so a frontend
     * on a sibling subdomain can read it and the browser sends it back on the same requests.
     */
    private static CookieCsrfTokenRepository csrfTokenRepository(SentretProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> {
            cookie.sameSite(properties.getCookieSameSite()).secure(properties.isCookieSecure());

            if (properties.getCookieDomain() != null) {
                cookie.domain(properties.getCookieDomain());
            }
        });
        return repository;
    }

    /**
     * The npm client sends heartbeat and renew without a CSRF header; a forged one can only keep an
     * existing session alive, so they are exempt. Apps add their own paths (typically the logout
     * URL the client calls) through csrf-ignored-paths. Everything else still needs the token.
     */
    private static String[] csrfExemptPaths(SentretProperties properties) {
        List<String> paths = new ArrayList<>(properties.getCsrfIgnoredPaths());

        if (properties.getHub().isEnabled()) {
            String basePath = properties.getHub().getBasePath();
            paths.add(basePath + "/heartbeat");
            paths.add(basePath + "/renew");
        }

        return paths.toArray(String[]::new);
    }

    /**
     * SPA flow (Spring Security docs, "Single-Page Applications"): the client reads the
     * XSRF-TOKEN cookie and sends its raw value in X-XSRF-TOKEN. The default handler expects a
     * masked (XOR) token and defers loading it, so the cookie would never be written and the raw
     * value would be rejected. Loading the token on every request keeps the cookie present.
     */
    private static CsrfTokenRequestAttributeHandler spaCsrfTokenRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    private CorsConfigurationSource corsConfigurationSource(SentretProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        // Patterns, not plain origins: "https://*.example.com" works and "*" does not throw on
        // every request when combined with credentials.
        config.setAllowedOriginPatterns(properties.getCorsAllowedOrigins());
        config.setAllowedMethods(CORS_ALLOWED_METHODS);
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
