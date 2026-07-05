package com.choruskube.core.executor;

/**
 * Returned by {@link WorkloadExecutor#execute} on successful launch.
 *
 * @param executionHandle runtime-specific identifier (K8s: Job name, Docker: container ID)
 * @param jobSecretHash   SHA-256 hash of the JOB_SECRET delivered to the agent
 */
public record ExecutionResult(String executionHandle, String jobSecretHash) {}
