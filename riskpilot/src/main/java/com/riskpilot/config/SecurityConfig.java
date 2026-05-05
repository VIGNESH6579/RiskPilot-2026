package com.riskpilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .httpBasic(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/frontend.html").permitAll()
                .requestMatchers("/api/initial-data").permitAll()
                .requestMatchers("/api/v1/health/**", "/api/v1/monitor/**", "/api/v1/data/**").permitAll()
                .requestMatchers("/api/v1/engine/health", "/api/v1/engine/state", "/api/v1/engine/candle-history").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/api/actuator/health", "/api/actuator/info").permitAll()
                .requestMatchers("/ws", "/ws/**", "/ws/signals/**", "/stomp", "/stomp/**").permitAll()
                .requestMatchers("/api/v1/engine/reset", "/api/v1/trading/engine/restart").hasRole("ADMIN")
                .requestMatchers("/api/v1/trading/status").permitAll()
                .requestMatchers("/api/v1/**").permitAll()
                .requestMatchers("/h2-console/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            );

        http.headers(headers -> headers.frameOptions(frame -> frame.disable()));
        
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
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
        // BUG-FIX: Added safe defaults so app starts in dev without env vars set.
        // In production, always override via ADMIN_USERNAME / ADMIN_PASSWORD env vars.
        @Value("${ADMIN_USERNAME:riskpilot-admin}") String adminUsername,
        @Value("${ADMIN_PASSWORD:changeme-set-in-prod}") String adminPassword,
        PasswordEncoder passwordEncoder
    ) {
        return new InMemoryUserDetailsManager(
            User.withUsername(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build()
        );
    }
}
