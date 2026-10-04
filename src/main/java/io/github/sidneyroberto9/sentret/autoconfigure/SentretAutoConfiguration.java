package io.github.sidneyroberto9.sentret.autoconfigure;

import io.github.sidneyroberto9.sentret.config.SentretProperties;
import io.github.sidneyroberto9.sentret.config.SentretSecurityValidator;
import io.github.sidneyroberto9.sentret.security.SentretAuthenticationFilter;
import io.github.sidneyroberto9.sentret.service.SentretCookieManager;
import io.github.sidneyroberto9.sentret.service.SentretService;
import io.github.sidneyroberto9.sentret.service.SentretUserService;
import io.github.sidneyroberto9.sentret.store.JdbcSentretSessionStore;
import io.github.sidneyroberto9.sentret.store.SentretSessionStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration
@ConditionalOnClass({JdbcTemplate.class, SecurityFilterChain.class})
@ConditionalOnProperty(prefix = "sentret", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SentretProperties.class)
@Import(SentretWebMvcConfiguration.class)
public class SentretAutoConfiguration {

    private static final List<String> CORS_ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    @Bean
    @ConditionalOnMissingBean
    public SentretSecurityValidator sentretSecurityValidator(SentretProperties properties) {
        return new SentretSecurityValidator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SentretCookieManager cookieManager(SentretProperties properties) {
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
            SentretCookieManager cookieManager,
            ApplicationEventPublisher eventPublisher
    ) {
        return new SentretService(store, properties, eventPublisher, cookieManager);
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
            SentretCookieManager cookieManager
    ) {
        return new SentretAuthenticationFilter(sentretService, cookieManager);
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
                        .requestMatchers(permitAll.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(sentretAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (properties.isCsrfEnabled()) {
            http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
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

    private CorsConfigurationSource corsConfigurationSource(SentretProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCorsAllowedOrigins());
        config.setAllowedMethods(CORS_ALLOWED_METHODS);
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
