package com.choruskube.core.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Single-tenant default: nothing to bind, so the work simply runs. */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class SingleTenantStoryScopeBinder implements StoryScopeBinder {

    @Override
    public void runInScopeOf(UUID storyId, Runnable work) {
        work.run();
    }
}
