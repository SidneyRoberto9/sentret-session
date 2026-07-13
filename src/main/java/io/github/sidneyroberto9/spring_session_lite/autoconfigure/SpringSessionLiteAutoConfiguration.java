package io.github.sidneyroberto9.spring_session_lite.autoconfigure;

import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteProperties;
import io.github.sidneyroberto9.spring_session_lite.config.SpringSessionLiteSecurityValidator;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSession;
import io.github.sidneyroberto9.spring_session_lite.domain.SpringSessionLiteSessionRepository;
import io.github.sidneyroberto9.spring_session_lite.scheduler.SpringSessionLiteCleanupTask;
import io.github.sidneyroberto9.spring_session_lite.security.SpringSessionLiteAuthenticationFilter;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteCookieManager;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteIpHasher;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteIpResolver;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteService;
import io.github.sidneyroberto9.spring_session_lite.service.SpringSessionLiteUserService;
import io.github.sidneyroberto9.spring_session_lite.store.JpaSpringSessionLiteSessionStore;
import io.github.sidneyroberto9.spring_session_lite.store.SpringSessionLiteSessionStore;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration(
        before = JpaRepositoriesAutoConfiguration.class,
        after = HibernateJpaAutoConfiguration.class
)
@AutoConfigurationPackage(basePackageClasses = SpringSessionLiteSession.class)
@ConditionalOnClass({EntityManagerFactory.class, SecurityFilterChain.class})
@ConditionalOnProperty(prefix = "spring-session-lite", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SpringSessionLiteProperties.class)
@Import(SpringSessionLiteWebMvcConfiguration.class)
public class SpringSessionLiteAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteSecurityValidator springSessionLiteSecurityValidator(SpringSessionLiteProperties properties) {
        return new SpringSessionLiteSecurityValidator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteIpResolver ipResolver(SpringSessionLiteProperties properties) {
        return new SpringSessionLiteIpResolver(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteIpHasher ipHasher(SpringSessionLiteProperties properties) {
        return new SpringSessionLiteIpHasher(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteCookieManager cookieManager(SpringSessionLiteProperties properties) {
        return new SpringSessionLiteCookieManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteSessionStore springSessionLiteSessionStore(SpringSessionLiteSessionRepository repository) {
        return new JpaSpringSessionLiteSessionStore(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteService springSessionLiteService(
            SpringSessionLiteProperties properties,
            SpringSessionLiteSessionStore store,
            SpringSessionLiteCookieManager cookieManager,
            SpringSessionLiteIpResolver ipResolver,
            SpringSessionLiteIpHasher ipHasher,
            ApplicationEventPublisher eventPublisher
    ) {
        return new SpringSessionLiteService(ipHasher, store, properties, ipResolver, eventPublisher, cookieManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteUserService springSessionLiteUserService() {
        return new SpringSessionLiteUserService();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringSessionLiteAuthenticationFilter springSessionLiteAuthenticationFilter(
            SpringSessionLiteService springSessionLiteService,
            SpringSessionLiteCookieManager cookieManager
    ) {
        return new SpringSessionLiteAuthenticationFilter(springSessionLiteService, cookieManager);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain sessionLiteSecurityFilterChain(
            HttpSecurity http,
            SpringSessionLiteAuthenticationFilter springSessionLiteAuthenticationFilter,
            SpringSessionLiteProperties properties
    ) throws Exception {

        AuthenticationEntryPoint entryPoint = (req, res, ex) -> {
            res.setStatus(HttpStatus.UNAUTHORIZED.value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Authentication required\"}");
        };

        // The opt-in /session/* endpoints controller lives in a separate @AutoConfiguration
        // (SpringSessionLiteEndpointsAutoConfiguration) so it can be conditioned independently on
        // endpoints-enabled. Bean-instantiation order across two distinct @AutoConfiguration
        // classes isn't guaranteed by before=/after=, so the permit-all path for /session/status
        // is wired here instead, in the one place that already builds the default chain.
        List<String> permitAll = new ArrayList<>(properties.getPermitAllPaths());
        if (properties.isEndpointsEnabled()) {
            permitAll.add(properties.getEndpointsBasePath() + "/status");
        }

        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .logout(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(permitAll.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(springSessionLiteAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (properties.isCsrfEnabled()) {
            http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
        } else {
            http.csrf(AbstractHttpConfigurer::disable);
        }

        if (properties.isCorsEnabled()) {
            http.cors(cors -> cors.configurationSource(corsConfigurationSource(properties)));
        } else {
            http.cors(AbstractHttpConfigurer::disable);
        }

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource(SpringSessionLiteProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCorsAllowedOrigins());
        config.setAllowedMethods(properties.getCorsAllowedMethods());
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(properties.isCorsAllowCredentials());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spring-session-lite", name = "cleanup-enabled", matchIfMissing = true)
    @EnableScheduling
    static class CleanupConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SpringSessionLiteCleanupTask springSessionLiteCleanupTask(SpringSessionLiteService springSessionLiteService) {
            return new SpringSessionLiteCleanupTask(springSessionLiteService);
        }
    }
}
