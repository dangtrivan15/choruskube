package com.choruskube.core.service;

public interface NamespaceDeprovisioner {

    /**
     * Deletes the given org namespace and all its resources. Idempotent: a {@code null} namespace
     * or an already-deleted namespace is a no-op. Single-tenant: always a no-op.
     *
     * @param namespace the org namespace to deprovision; may be {@code null}
     */
    void deprovisionByNamespace(String namespace);
}
