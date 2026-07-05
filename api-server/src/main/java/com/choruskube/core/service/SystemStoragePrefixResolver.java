package com.choruskube.core.service;

import com.choruskube.core.config.SingleTenant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link StoragePrefixResolver} for the OSS / single-tenant deployment. Active when {@code
 * auth.enabled} is absent or {@code false} (the default).
 *
 * <p>Both methods return {@link SingleTenant#SLUG}. In single-tenant mode every resource belongs to
 * the system org, so the prefix is always the system slug.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class SystemStoragePrefixResolver implements StoragePrefixResolver {

    @Override
    public String storagePrefixForRun(UUID runId) {
        return SingleTenant.SLUG;
    }

    @Override
    public String currentStoragePrefix() {
        return SingleTenant.SLUG;
    }
}
