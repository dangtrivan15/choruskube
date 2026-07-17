package com.choruskube.core.dto;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID storyId,
        String title,
        String description,
        String status,
        SoftwareProjectRef softwareProject,
        List<RepoRef> repos,
        @Nullable UUID latestRunId,
        @Nullable String latestRunStatus,
        Instant createdAt,
        Instant updatedAt) {}
