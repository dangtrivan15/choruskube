package com.choruskube.core.service;

import com.choruskube.core.config.ArtifactPresigners;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class PresignService {

    private static final int PRESIGN_TTL_SECONDS = 900; // 15 minutes
    private static final long CACHE_TTL_MILLIS = 720_000; // 12 minutes (safe margin below 15 min)

    private final ArtifactPresigners artifactPresigners;
    private final ArtifactSigningEndpointResolver endpointResolver;
    private final String bucket;
    private final ConcurrentHashMap<String, CachedUrl> cache = new ConcurrentHashMap<>();

    public PresignService(
            ArtifactPresigners artifactPresigners,
            ObjectProvider<ArtifactSigningEndpointResolver> endpointResolverProvider,
            @Value("${objectstore.bucket}") String bucket) {
        this.artifactPresigners = artifactPresigners;
        this.endpointResolver = endpointResolverProvider.getIfAvailable(OwnArtifactSigningEndpoint::new);
        this.bucket = bucket;
    }

    public String generatePresignedUrl(UUID runId, String objectPath, String method, Long contentLength) {
        String verb = method.toUpperCase();
        if (!verb.equals("GET") && !verb.equals("PUT")) {
            throw new IllegalArgumentException("Unsupported method: " + method + ". Only GET and PUT are allowed.");
        }
        // A PUT's signed content-length is what lets the store refuse any other body length; a
        // GET carries no body, so a size here would sign a check the request can never satisfy.
        if (verb.equals("PUT")) {
            if (contentLength == null || contentLength < 0) {
                throw new IllegalArgumentException("PUT requires a non-null, non-negative contentLength");
            }
        } else if (contentLength != null) {
            throw new IllegalArgumentException("GET must not carry a contentLength");
        }

        // Resolved and validated before any cache lookup or presigner call: a seam that throws
        // must fail this request outright, never fall back to the own endpoint, and never reach
        // forEndpoint with an answer the cache or the presigner would mis-sign for.
        URI endpoint = resolveEndpoint(runId);

        // The endpoint and size are both part of the cache key: a URL signed for one endpoint or
        // one length is never handed to a caller whose run resolved a different endpoint, or who
        // declared a different length, than the URL it would return.
        String cacheKey = endpoint + "|" + verb + "|" + objectPath + "|" + contentLength;
        CachedUrl cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.url;
        }

        S3Presigner presigner = artifactPresigners.forEndpoint(endpoint);
        try {
            Duration ttl = Duration.ofSeconds(PRESIGN_TTL_SECONDS);
            String url = verb.equals("GET")
                    ? presigner
                            .presignGetObject(GetObjectPresignRequest.builder()
                                    .signatureDuration(ttl)
                                    .getObjectRequest(GetObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(objectPath)
                                            .build())
                                    .build())
                            .url()
                            .toString()
                    : presigner
                            .presignPutObject(PutObjectPresignRequest.builder()
                                    .signatureDuration(ttl)
                                    .putObjectRequest(PutObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(objectPath)
                                            .contentLength(contentLength)
                                            .build())
                                    .build())
                            .url()
                            .toString();
            cache.put(cacheKey, new CachedUrl(url, Instant.now()));
            return url;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL for " + objectPath, e);
        }
    }

    private URI resolveEndpoint(UUID runId) {
        return endpointResolver
                .endpointFor(runId)
                .map(PresignService::requireValidSigningEndpoint)
                .orElseGet(artifactPresigners::ownEndpoint);
    }

    // ArtifactSigningEndpointResolver's contract limits a seam answer to scheme + host + optional
    // port; anything else (a path, query, fragment, userinfo, or non-http(s) scheme) would sign a
    // URL whose authority doesn't match what the seam actually named.
    private static URI requireValidSigningEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalStateException("Artifact signing endpoint must be http or https: " + endpoint);
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalStateException("Artifact signing endpoint must carry a host: " + endpoint);
        }
        String path = endpoint.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new IllegalStateException("Artifact signing endpoint must carry no path: " + endpoint);
        }
        if (endpoint.getQuery() != null) {
            throw new IllegalStateException("Artifact signing endpoint must carry no query: " + endpoint);
        }
        if (endpoint.getFragment() != null) {
            throw new IllegalStateException("Artifact signing endpoint must carry no fragment: " + endpoint);
        }
        if (endpoint.getUserInfo() != null) {
            throw new IllegalStateException("Artifact signing endpoint must carry no userinfo: " + endpoint);
        }
        return endpoint;
    }

    private record CachedUrl(String url, Instant createdAt) {
        boolean isExpired() {
            return Instant.now().toEpochMilli() - createdAt.toEpochMilli() > CACHE_TTL_MILLIS;
        }
    }
}
