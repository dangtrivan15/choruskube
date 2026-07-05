package com.choruskube.core.dto;

import java.util.UUID;

public record PredecessorOutput(
        UUID templateNodeId, String label, String result, String artifactRefs, UUID nodeExecutionId) {}
