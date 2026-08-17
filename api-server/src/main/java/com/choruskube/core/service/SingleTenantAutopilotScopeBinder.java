package com.choruskube.core.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Single-tenant default: one installation, one organisation, nothing to bind — so the pass simply
 * runs.
 *
 * <p>Gated the way every other OSS seam here is, so a downstream implementation replaces this bean
 * rather than colliding with it — see {@link SingleTenantAutopilotResolver}, {@code
 * NoOpScopeProvider}, {@link AllEpicsCandidateSource}.
 *
 * <p>Deliberately carries no {@code @Transactional} and no collaborators. The pass it runs owns its
 * own transaction boundaries — see {@link AutopilotScopeBinder} — and a binder with something to
 * inject would be a binder that could acquire one by accident.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class SingleTenantAutopilotScopeBinder implements AutopilotScopeBinder {

    @Override
    public void runInScopeOf(UUID autopilotId, Runnable pass) {
        // No try/finally, because nothing was bound: the requirements the interface states are
        // obligations on an implementation that binds something. Not swallowing is the one this
        // shares, and running the pass unguarded is how it keeps it.
        pass.run();
    }
}
