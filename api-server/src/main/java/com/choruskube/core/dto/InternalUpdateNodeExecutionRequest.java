package com.choruskube.core.dto;

public record InternalUpdateNodeExecutionRequest(
        String status, String result, String artifactRefs, String podName, String jobSecretHash, String errorMessage) {}
