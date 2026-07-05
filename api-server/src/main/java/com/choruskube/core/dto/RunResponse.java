package com.choruskube.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        @Nullable RunFeatureProposalSummary featureProposal,
        @Nullable SoftwareProjectRef softwareProject) {}
