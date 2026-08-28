package com.choruskube.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** @param autopilotId set when the Autopilot started this run, null when a person did */
public record RunResponse(
        UUID id,
        UUID graphTemplateId,
        String templateName,
        String name,
        String status,
        String externalRunId,
        int graphVersion,
        JsonNode graphSnapshot,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        List<NodeExecutionResponse> nodeExecutions,
        List<RunPullRequestResponse> pullRequests,
        String inputArtifactRefs,
        @Nullable String promptText,
        @Nullable RunTaskSummary task,
        @Nullable UUID autopilotId,
        @Nullable SoftwareProjectRef softwareProject) {}
