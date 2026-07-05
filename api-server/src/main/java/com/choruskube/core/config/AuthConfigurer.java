package com.choruskube.core.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Strategy for configuring HTTP security for {@code /api/**} endpoints.
 *
 * <p>Implementations are selected at startup by Spring {@code @ConditionalOnProperty}
 * keyed on {@code auth.enabled}:
 * <ul>
 *   <li>the auth-enabled configurer when {@code auth.enabled=true}</li>
 *   <li>{@link NoAuthConfigurer} when {@code auth.enabled} is unset or false (default)</li>
 * </ul>
 *
 * <p>Neither impl is responsible for {@code /internal/**} authentication — that is
 * always handled by {@link InternalAuthFilter}, which {@link SecurityConfig} adds
 * to the chain before delegating to the configurer.
 */
public interface AuthConfigurer {

    /**
     * Apply auth-mode-specific HTTP security configuration to the given builder.
     * Called once at startup from {@link SecurityConfig#securityFilterChain(HttpSecurity)}.
     */
    void configure(HttpSecurity http) throws Exception;
}
