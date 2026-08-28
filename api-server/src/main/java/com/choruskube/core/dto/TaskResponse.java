package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Readiness;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code readiness} is populated by the Roadmap Graph View and by {@code list()};
 * single-item reads (create/get/update) leave it {@code null}, since they have no reason to join
 * dependency edges just to return the one item just written. {@code recentRuns}/{@code
 * totalRunCount} stay empty/zero everywhere except the Roadmap Graph View.
 * {@code recentRuns} is capped (see {@code RECENT_RUNS_LIMIT}); {@code totalRunCount} reflects the
 * true count even when the embedded list is truncated — page through
 * {@code GET /api/v1/tasks/{id}/runs} for the rest.
 *
 * <p>{@code priority} mirrors {@code EpicResponse}/{@code StoryResponse}'s own string-valued
 * {@code priority} field — never null; a Task with no explicit priority reads back {@code "medium"}.
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
        Instant updatedAt,
        String priority) {}
