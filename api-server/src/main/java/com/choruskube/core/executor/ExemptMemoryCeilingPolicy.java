package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Default {@link WorkloadMemoryCeilingPolicy}: every run is exempt.
 *
 * <p><b>Not a Spring bean.</b> {@code WorkloadService} holds it as the {@code ObjectProvider}
 * fallback, so a real implementation replaces it by existing rather than by bean-scan ordering.
 */
public class ExemptMemoryCeilingPolicy implements WorkloadMemoryCeilingPolicy {

    @Override
    public boolean exempt(UUID runId) {
        return true;
    }
}
