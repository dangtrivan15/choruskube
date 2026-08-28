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
        List<ResolvedArtifactGroup> requiredArtifacts,
        RoadmapCandidatesDocument candidateBreakdown,
        /**
         * Why this run was escalated to the Supervisor, or {@code null} for an ordinary gate, or
         * for a gate execution that isn't the Supervisor's, or when it is the Supervisor but
         * nothing has escalated yet.
         */
        EscalationContext escalation) {}
