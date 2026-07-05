package com.choruskube.core.executor;

/**
 * Describes the identity under which an agent container runs.
 *
 * @param name           K8s: ServiceAccount name; Docker: unused
 * @param runAsUser      numeric UID the container process runs as
 * @param allowApiAccess K8s: AutomountServiceAccountToken; Docker: always false
 */
public record IdentitySpec(String name, long runAsUser, boolean allowApiAccess) {

    /** Creates a default (empty) identity spec. */
    public static IdentitySpec empty() {
        return new IdentitySpec(null, 0, false);
    }
}
