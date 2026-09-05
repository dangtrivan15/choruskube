package com.choruskube.core.dto;

import java.util.UUID;

/**
 * What a Worker reads from the {@code /worker} node-execution GET: the execution's id and status
 * (for the callback's finalized check), plus the namespace its workload runs in so teardown and
 * hash-recovery can address resources by name within it. {@code namespace} is {@code ""} in a
 * deployment that runs no per-org namespaces.
 *
 * <p>The field names here are a cross-module contract with the Go worker's {@code
 * workload.NodeExecution} — keep {@code id}/{@code status}/{@code namespace} identical on both sides.
 */
public record WorkerNodeExecutionResponse(UUID id, String status, String namespace) {}
