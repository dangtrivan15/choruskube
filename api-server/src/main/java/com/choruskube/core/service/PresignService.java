package com.choruskube.core.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
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

    private final S3Presigner s3Presigner;
    private final String bucket;
    private final ConcurrentHashMap<String, CachedUrl> cache = new ConcurrentHashMap<>();

    public PresignService(S3Presigner s3Presigner, @Value("${objectstore.bucket}") String bucket) {
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
    }

    public String generatePresignedUrl(String objectPath, String method) {
        String verb = method.toUpperCase();
        if (!verb.equals("GET") && !verb.equals("PUT")) {
            throw new IllegalArgumentException("Unsupported method: " + method + ". Only GET and PUT are allowed.");
        }

        String cacheKey = verb + ":" + objectPath;
        CachedUrl cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.url;
        }

        try {
            Duration ttl = Duration.ofSeconds(PRESIGN_TTL_SECONDS);
            String url = verb.equals("GET")
                    ? s3Presigner
                            .presignGetObject(GetObjectPresignRequest.builder()
                                    .signatureDuration(ttl)
                                    .getObjectRequest(GetObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(objectPath)
                                            .build())
                                    .build())
                            .url()
                            .toString()
                    : s3Presigner
                            .presignPutObject(PutObjectPresignRequest.builder()
                                    .signatureDuration(ttl)
                                    .putObjectRequest(PutObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(objectPath)
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

    private record CachedUrl(String url, Instant createdAt) {
        boolean isExpired() {
            return Instant.now().toEpochMilli() - createdAt.toEpochMilli() > CACHE_TTL_MILLIS;
        }
    }
}
