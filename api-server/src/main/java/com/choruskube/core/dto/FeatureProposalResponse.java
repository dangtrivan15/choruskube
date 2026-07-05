package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FeatureProposalResponse(
        UUID id,
        String title,
        String description,
        String motivation,
        String status,
        SoftwareProjectRef softwareProject,
        List<RepoRef> repos,
        UUID workflowRunId,
        String workflowRunStatus,
        Instant createdAt,
        Instant updatedAt) {}
