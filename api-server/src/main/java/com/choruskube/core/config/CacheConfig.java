package com.choruskube.core.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine cache for the public landing-page metrics endpoint.
 *
 * <p>NOTE: if the {@code LandingMetricsResponse} shape ever changes (added/removed
 * field), restart the api-server pod after deploy so cached entries from the
 * old shape do not linger up to 24h. Caffeine doesn't deserialize so this is
 * functionally harmless today — but becomes a correctness bug if the cache
 * backend is ever swapped to Redis.
 *
 * <p>NOTE: {@code cache.landing-metrics.ttl-seconds} is read once at bean construction.
 * Changing the value via configmap requires a pod rollout. flux's standard
 * configmap-change-rolls-the-pod behaviour handles this automatically.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String LANDING_METRICS = "landingMetrics";

    @Bean
    public CacheManager cacheManager(@Value("${cache.landing-metrics.ttl-seconds:86400}") long ttlSeconds) {
        CaffeineCacheManager mgr = new CaffeineCacheManager(LANDING_METRICS);
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(16));
        return mgr;
    }
}
