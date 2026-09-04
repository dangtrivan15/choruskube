package com.choruskube.core.dto;

/** What a Worker reports back after it has launched a workload itself. */
public record CompleteWorkloadRequest(String podName, String jobSecretHash) {}
