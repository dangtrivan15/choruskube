package com.choruskube.core.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Wires the S3-compatible object-store clients using the AWS SDK v2. The client talks to any
 * S3-compatible backend (MinIO, Ceph, Cloudflare R2, AWS S3) — the vendor is entirely a function
 * of {@code objectstore.endpoint} and its credentials, not the client library.
 */
@Configuration
public class ObjectStoreConfig {

    @Value("${objectstore.endpoint}")
    private String endpoint;

    @Value("${objectstore.access-key}")
    private String accessKey;

    @Value("${objectstore.secret-key}")
    private String secretKey;

    // AWS SDK v2 requires a region even against a non-AWS endpoint, where it only feeds request
    // signing. us-east-1 is the conventional default for S3-compatible stores; override via
    // OBJECT_STORE_REGION when a backend enforces a specific one.
    @Value("${objectstore.region:us-east-1}")
    private String region;

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    // S3-compatible stores address buckets by path (endpoint/bucket/key), not by the AWS-style
    // virtual-host subdomain (bucket.endpoint) — a custom endpoint has no per-bucket DNS.
    private S3Configuration pathStyle() {
        return S3Configuration.builder().pathStyleAccessEnabled(true).build();
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials())
                .serviceConfiguration(pathStyle())
                // Only emit/validate object checksums when a request explicitly asks for one. The
                // SDK's newer "when_supported" default adds a streaming CRC trailer to uploads that
                // some S3-compatible backends reject; this keeps transfers portable across backends.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials())
                .serviceConfiguration(pathStyle())
                .build();
    }
}
