package com.choruskube.core.executor;

import java.util.List;
import java.util.UUID;

/**
 * Strategy interface for launching agent containers.
 *
 * <p>Implementations include a Kubernetes executor (Kubernetes Jobs, not part of OSS
 * core) and {@link SingleTenantDockerExecutor} (Docker Engine containers).
 *
 * <p>Lifecycle operations (cleanup/getLogs/terminate) are keyed solely by execution id.
 * Any placement detail an implementation needs (e.g. a Kubernetes namespace) is resolved
 * by that implementation from the execution id; it is not part of this contract.
 */
public interface WorkloadExecutor {

    /**
     * Launches an agent container with the given parameters.
     *
     * @return result containing the runtime handle and JOB_SECRET hash
     */
    ExecutionResult execute(ExecutionParams params);

    /**
     * Removes all resources associated with a completed execution.
     * K8s: deletes Job, ConfigMap, and Secret. Docker: removes container and temp files.
     * Idempotent.
     */
    void cleanup(UUID executionId);

    /**
     * Returns recent log output from the agent container.
     */
    String getLogs(UUID executionId, int tailLines);

    /**
     * Stops a running execution.
     * K8s: sets activeDeadlineSeconds=1 on the Job. Docker: sends SIGTERM to container.
     * Idempotent.
     */
    void terminate(UUID executionId);

    /**
     * Returns info about all running/completed executions managed by this executor.
     * K8s uses a cluster-wide label-scoped list; Docker lists containers by label.
     */
    List<ExecutionInfo> listExecutions();

    /**
     * Checks the executor backend connectivity.
     */
    void healthCheck();
}
