package com.choruskube.core.dto;

/**
 * Response body for {@code GET /internal/workloads/{executionId}/logs}.
 */
public record WorkloadLogsResponse(String logs) {}
