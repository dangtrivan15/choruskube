package com.choruskube.core.dto;

/** What a Worker needs to launch a workload itself, resolved server-side (DB + config access). */
public record PrepareWorkloadResponse(
        String image,
        boolean enableDocker,
        String claudeOAuthToken,
        String githubTokenUrl,
        RegistryCredentialsDto registryCredentials,
        String namespace,
        String serviceAccount) {

    public record RegistryCredentialsDto(String host, String username, String password) {}
}
