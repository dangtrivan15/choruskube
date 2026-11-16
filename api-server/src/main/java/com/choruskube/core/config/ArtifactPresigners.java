package com.choruskube.core.config;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds and caches an {@link S3Presigner} per endpoint, so a presign request can sign for a
 * different endpoint than the server's own without the server's own object-store traffic ever
 * moving off {@link #ownEndpoint()}. Every presigner is built from the same credential, region and
 * path-style config as the server's own — only the endpoint varies.
 */
public class ArtifactPresigners implements AutoCloseable {

    private final URI ownEndpoint;
    private final StaticCredentialsProvider credentials;
    private final Region region;
    private final S3Configuration pathStyle;
    private final ConcurrentHashMap<URI, S3Presigner> presigners = new ConcurrentHashMap<>();

    public ArtifactPresigners(
            URI ownEndpoint, StaticCredentialsProvider credentials, Region region, S3Configuration pathStyle) {
        this.ownEndpoint = ownEndpoint;
        this.credentials = credentials;
        this.region = region;
        this.pathStyle = pathStyle;
    }

    public URI ownEndpoint() {
        return ownEndpoint;
    }

    public S3Presigner forEndpoint(URI endpoint) {
        return presigners.computeIfAbsent(endpoint, e -> S3Presigner.builder()
                .endpointOverride(e)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build());
    }

    @Override
    public void close() {
        for (S3Presigner presigner : presigners.values()) {
            presigner.close();
        }
    }
}
