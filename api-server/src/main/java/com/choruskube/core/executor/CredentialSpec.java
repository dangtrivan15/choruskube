package com.choruskube.core.executor;

/**
 * Describes a secret/credential that must be available to an agent container.
 *
 * <p>The meaning of {@code source} depends on the executor backend:
 * <ul>
 *   <li>K8s: Kubernetes Secret object name (e.g., "anthropic-api-key")</li>
 *   <li>Docker: host filesystem path (e.g., "/home/user/.secrets/anthropic-api-key")</li>
 * </ul>
 *
 * @param source    where the credential comes from (executor-specific)
 * @param delivery  how the credential reaches the container: "env" or "volume"
 * @param mountPath container-side path (required when delivery="volume")
 * @param readOnly  whether the mounted volume is writable (default: true)
 */
public record CredentialSpec(String source, String delivery, String mountPath, Boolean readOnly) {

    /** Returns true if readOnly is null or true. */
    public boolean isReadOnly() {
        return readOnly == null || readOnly;
    }
}
