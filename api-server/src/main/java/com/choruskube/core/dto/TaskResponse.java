package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Readiness;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code readiness} is {@code null}, and {@code recentRuns}/{@code totalRunCount} are empty/zero,
 * unless this response was assembled by the Roadmap Graph View (Decision 2, Decision 3) — plain
 * Task CRUD reads (create/get/update/list) have no reason to join dependency edges or embed run
 * history. {@code recentRuns} is capped (see {@code RECENT_RUNS_LIMIT}); {@code totalRunCount}
 * reflects the true count even when the embedded list is truncated — page through
 * {@code GET /api/v1/tasks/{id}/runs} for the rest.
 */
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
        @Nullable Readiness readiness,
        List<RunSummary> recentRuns,
        long totalRunCount,
        Instant createdAt,
        Instant updatedAt) {}
