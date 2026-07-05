package com.choruskube.core.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Defines the application's {@link CorsConfigurationSource} bean.
 *
 * <p>Lives outside the auth-enabled configurer so the bean exists in any auth mode —
 * bean-level tests (e.g. {@code CorsConfigurationSourceTest}) autowire it directly regardless of
 * which {@link AuthConfigurer} is active. Only the auth-enabled configurer actually
 * wires it into the security chain via {@code http.cors(...)}; {@code NoAuthConfigurer} does not.
 */
@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins:http://localhost:13000}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
