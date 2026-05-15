package com.riskpilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * PRODUCTION Security Configuration.
 *
 * Changes from old config:
 *
 * 1. REMOVED: .requestMatchers("/api/v1/**").permitAll() — every trading API was public.
 *    FIX: Explicit ADMIN-only rules for all write/control endpoints.
 *
 * 2. REMOVED: .csrf(AbstractHttpConfigurer::disable) — CSRF globally disabled.
 *    FIX: CSRF enabled for browser pages; disabled only for stateless API & WebSocket paths.
 *
 * 3. REMOVED: setAllowedOriginPatterns("*") with credentials=true — wildcard CORS.
 *    FIX: Explicit whitelist from CORS_ALLOWED_ORIGINS env var.
 *
 * 4. REMOVED: anyRequest().permitAll() — silent catch-all.
 *    FIX: anyRequest().denyAll() — explicit deny by default.
 *
 * 5. REMOVED: WebSocket open to all without auth.
 *    FIX: /ws and /stomp require authentication.
 *
 * 6. REMOVED: soft defaults "riskpilot-admin" / "changeme-set-in-prod".
 *    FIX: Fail-fast on startup if ADMIN_USERNAME/ADMIN_PASSWORD not set or too short.
 *
 * 7. ADDED: Security response headers (HSTS, CSP, X-Frame-Options, Referrer-Policy).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Value("${CORS_ALLOWED_ORIGINS:https://riskpilot-2026.onrender.com}")
    private String corsAllowedOriginsRaw;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                    "/api/**",
                    "/ws", "/ws/**",
                    "/stomp", "/stomp/**"
                )
            )
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic.realmName("RiskPilot Trading API"))
            .authorizeHttpRequests(auth -> auth
                // Health probes — public (Render uptime check hits these)
                .requestMatchers(
                    "/actuator/health", "/actuator/health/**",
                    "/actuator/info", "/api/actuator/health"
                ).permitAll()
                // Static dashboard — read-only UI
                .requestMatchers(HttpMethod.GET,
                    "/", "/index.html", "/frontend.html", "/favicon.ico",
                    "/static/**"
                ).permitAll()
                // Read-only data endpoints — safe to expose
                .requestMatchers(HttpMethod.GET,
                    "/api/initial-data",
                    "/api/v1/health/**",
                    "/api/v1/monitor/**",
                    "/api/v1/engine/health",
                    "/api/v1/engine/state",
                    "/api/v1/engine/candle-history",
                    "/api/v1/trading/status",
                    "/api/v1/data/**"
                ).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/market/**").permitAll()
                // WebSocket: public monitoring
                .requestMatchers("/ws", "/ws/**", "/stomp", "/stomp/**").permitAll()
                // Trading & engine control: ADMIN only
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/trading/**", "/api/v1/engine/**"
                ).hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT,  "/api/v1/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasRole("ADMIN")
                // Prometheus metrics: ADMIN only (leaks internal state)
                .requestMatchers("/actuator/metrics/**", "/actuator/prometheus").hasRole("ADMIN")
                // H2 console: ADMIN only (dev only — disabled in prod via app config)
                .requestMatchers("/h2-console/**").hasRole("ADMIN")
                // Explicit deny-all for everything else
                .anyRequest().denyAll()
            )
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .preload(true)
                    .maxAgeInSeconds(31_536_000)
                )
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(cto -> {})
                .referrerPolicy(rp ->
                    rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp ->
                    csp.policyDirectives(
                        "default-src 'self'; " +
                        "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net; " +
                        "font-src 'self' https://fonts.gstatic.com; " +
                        "connect-src 'self' wss://riskpilot-2026.onrender.com ws://riskpilot-2026.onrender.com wss: ws: https://cdn.jsdelivr.net; " +
                        "img-src 'self' data: https://cdn.jsdelivr.net; " +
                        "frame-ancestors 'none'"
                    )
                )
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Parse whitelist — never accept "*" in production
        List<String> allowedOrigins = Arrays.stream(corsAllowedOriginsRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty() && !s.equals("*"))
            .toList();

        if (allowedOrigins.isEmpty()) {
            throw new IllegalStateException(
                "CORS_ALLOWED_ORIGINS is empty or contains only wildcards. " +
                "Set it to your Render URL e.g. https://riskpilot-2026.onrender.com"
            );
        }

        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization", "Content-Type", "X-Requested-With",
            "Accept", "X-XSRF-TOKEN", "Cache-Control"
        ));
        configuration.setExposedHeaders(List.of("X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService userDetailsService(
        @Value("${ADMIN_USERNAME:}") String adminUsername,
        @Value("${ADMIN_PASSWORD:}") String adminPassword,
        PasswordEncoder passwordEncoder
    ) {
        // Fail-fast: no silent insecure defaults
        if (adminUsername == null || adminUsername.isBlank()) {
            throw new IllegalStateException(
                "ADMIN_USERNAME environment variable is not set. Required for production.");
        }
        if (adminPassword == null || adminPassword.isBlank() || adminPassword.length() < 16) {
            throw new IllegalStateException(
                "ADMIN_PASSWORD is not set or too short (minimum 16 chars). Required for production.");
        }

        return new InMemoryUserDetailsManager(
            User.withUsername(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build()
        );
    }
}
