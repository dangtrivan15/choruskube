package com.choruskube.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Thin orchestrator for HTTP security. Wires {@link InternalAuthFilter} into the
 * chain (always-on for {@code /internal/**} shared-secret auth) and delegates the
 * rest of HTTP security configuration to the active {@link AuthConfigurer}
 * strategy bean, selected at startup by {@code @ConditionalOnProperty}
 * keyed on {@code auth.enabled}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final InternalAuthFilter internalAuthFilter;
    private final AuthConfigurer authConfigurer;

    public SecurityConfig(InternalAuthFilter internalAuthFilter, AuthConfigurer authConfigurer) {
        this.internalAuthFilter = internalAuthFilter;
        this.authConfigurer = authConfigurer;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class);
        authConfigurer.configure(http);
        return http.build();
    }
}
