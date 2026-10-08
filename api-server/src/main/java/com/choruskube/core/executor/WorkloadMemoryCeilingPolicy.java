package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Decides whether a workload's per-project memory requests may exceed the deployment's memory
 * limits. The Worker enforces the answer against the limits of the data plane it runs on, which this
 * module never sees: exempt, each limit is derived from its request; not exempt, a request above
 * the deployment limit fails the launch.
 *
 * <p>The default is {@link ExemptMemoryCeilingPolicy}: a single-tenant deployment's projects are
 * all set by its operator, who owns the limits being exceeded.
 */
public interface WorkloadMemoryCeilingPolicy {

    /**
     * @param runId the run whose workload is being prepared
     * @return {@code true} when this run's memory requests may exceed the deployment's limits
     */
    boolean exempt(UUID runId);
}
