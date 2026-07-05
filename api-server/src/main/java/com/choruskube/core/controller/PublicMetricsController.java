package com.choruskube.core.controller;

import com.choruskube.core.dto.LandingMetricsResponse;
import com.choruskube.core.service.PublicMetricsService;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, anonymous landing-page metrics endpoint. See
 * {@link com.choruskube.core.dto.LandingMetricsResponse} for the wire shape.
 *
 * <p>Path namespace {@code /api/public/**} is allow-listed in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/public/v1")
public class PublicMetricsController {

    private final PublicMetricsService service;

    public PublicMetricsController(PublicMetricsService service) {
        this.service = service;
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/landing-metrics")
    public ResponseEntity<LandingMetricsResponse> getLandingMetrics() {
        LandingMetricsResponse body = service.getLandingMetrics();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(body.cacheTtlSeconds(), TimeUnit.SECONDS)
                        .cachePublic())
                .body(body);
    }
}
