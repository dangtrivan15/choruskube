package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Decides whether a workload's per-project memory requests may exceed the deployment's default
 * requests. The Worker enforces the answer against the defaults of the data plane it runs on, which
 * this module never sees: not exempt, a request above the default fails the launch. Either way each
 * memory limit is derived from its request.
 *
 * <p>The default is {@link ExemptMemoryCeilingPolicy}: a single-tenant deployment's projects are
 * all set by its operator, who owns the defaults being exceeded.
 */
public interface WorkloadMemoryCeilingPolicy {

    /**
     * @param runId the run whose workload is being prepared
     * @return {@code true} when this run's memory requests may exceed the deployment's defaults
     */
    boolean exempt(UUID runId);
}
