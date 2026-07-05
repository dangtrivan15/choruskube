package com.choruskube.core.service;

import java.util.UUID;

public interface StoragePrefixResolver {

    /**
     * Object-storage path prefix (org slug) for a run-scoped or agent-scoped artifact.
     *
     * @param runId the workflow run id whose org slug should be resolved
     * @return the org slug (never null)
     */
    String storagePrefixForRun(UUID runId);

    /**
     * Object-storage path prefix for the current request's tenant (request-scoped uploads). Callers
     * must be within a request context — implementations that delegate to {@code TenantContext} will
     * throw {@link com.choruskube.core.exception.UnresolvableTenantException} if no context is
     * active.
     *
     * @return the org slug (never null)
     */
    String currentStoragePrefix();
}
