package com.choruskube.core.dto;

/**
 * Response body for {@code POST /internal/workloads/{runId}/{nodeExecId}}.
 */
public record CreateWorkloadResponse(String executionHandle, String jobSecretHash) {}
