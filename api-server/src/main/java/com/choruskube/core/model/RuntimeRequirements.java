package com.choruskube.core.model;

/**
 * Per-SoftwareProject runtime needs surfaced to the workload layer.
 * Future fields slot in here additively; aggregation rules live on RepoGroup.
 */
public record RuntimeRequirements(String agentImage, boolean enableDocker) {}
