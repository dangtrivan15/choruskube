package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NodeExecutionResponse(
        UUID id,
        UUID templateNodeId,
        String status,
        String result,
        String decision,
        String podName,
        int iteration,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        int graphVersion,
        String artifactRefs,
        String label,
        String loopGroup,
        String reviewerType,
        List<UUID> traversedEdgeIds,
        List<ResolvedArtifactGroup> requiredArtifacts) {}
