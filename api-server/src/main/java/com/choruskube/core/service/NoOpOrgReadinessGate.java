package com.choruskube.core.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link OrgReadinessGate} for the OSS / single-tenant deployment. Active when {@code auth.enabled}
 * is absent or {@code false} (the default).
 *
 * <p>Both gates are no-ops: single-tenant has no per-org namespace provisioning lifecycle to wait on
 * and no per-org running-jobs constraint, so git-repo create and the docker toggle are always
 * allowed (matching the behavior before the seam was introduced — core never created org namespaces).
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpOrgReadinessGate implements OrgReadinessGate {

    @Override
    public void assertReadyForCreate() {
        // Single-tenant: no org namespace lifecycle to gate on.
    }

    @Override
    public void assertNoRunningJobsForDockerToggle(UUID gitRepoId) {
        // Single-tenant: no per-org running-jobs constraint.
    }
}
