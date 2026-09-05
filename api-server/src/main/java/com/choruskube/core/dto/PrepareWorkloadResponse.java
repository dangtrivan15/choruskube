package com.choruskube.core.dto;

/** What a Worker needs to launch a workload itself, resolved server-side (DB + config access). */
public record PrepareWorkloadResponse(
        String image,
        boolean enableDocker,
        String claudeOAuthToken,
        String githubTokenUrl,
        RegistryCredentialsDto registryCredentials,
        String namespace,
        String serviceAccount,
        RegistryMirrorDto registryMirror) {

    public record RegistryCredentialsDto(String host, String username, String password) {}

    /** Null when this deployment provisions no registry mirror for the workload. */
    public record RegistryMirrorDto(String mirror, String buildCache, String depProxyBase) {}
}
