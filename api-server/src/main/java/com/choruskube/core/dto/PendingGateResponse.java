package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PendingGateResponse(
        UUID nodeExecutionId,
        UUID runId,
        String runStatus,
        String runName,
        String nodeLabel,
        int iteration,
        Integer timeoutSeconds,
        Instant waitingSince,
        String status,
        List<PredecessorOutput> predecessorOutputs,
        List<ResolvedArtifactGroup> requiredArtifacts,
        List<String> decisionOptions) {}
