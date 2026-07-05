package com.choruskube.core.executor;

import java.util.List;
import java.util.UUID;

/**
 * No-op executor for environments where workload execution is not configured.
 * All methods throw {@link UnsupportedOperationException}.
 */
public class NoopWorkloadExecutor implements WorkloadExecutor {

    @Override
    public ExecutionResult execute(ExecutionParams params) {
        throw new UnsupportedOperationException("No executor configured (executor.type=none)");
    }

    @Override
    public void cleanup(UUID executionId) {
        throw new UnsupportedOperationException("No executor configured (executor.type=none)");
    }

    @Override
    public String getLogs(UUID executionId, int tailLines) {
        throw new UnsupportedOperationException("No executor configured (executor.type=none)");
    }

    @Override
    public void terminate(UUID executionId) {
        throw new UnsupportedOperationException("No executor configured (executor.type=none)");
    }

    @Override
    public List<ExecutionInfo> listExecutions() {
        throw new UnsupportedOperationException("No executor configured (executor.type=none)");
    }

    @Override
    public void healthCheck() {
        throw new UnsupportedOperationException("No executor configured (executor.type=none)");
    }
}
