package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Describes a running or completed execution, used by {@link WorkloadExecutor#listExecutions}.
 */
public record ExecutionInfo(UUID nodeExecutionId, UUID runId, String executionHandle) {}
