package com.choruskube.core.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @param readyItemCount How much of this Epic's work could be started right now: descendant
 *     Tasks that are still in {@code backlog} AND {@code Readiness.READY} (roadmap "ready to
 *     start" filter) — computed at read time from the same {@code
 *     EpicReadinessAssembler} walk the Story/Task list endpoints use, never persisted. Tasks
 *     only: Stories and the Epic itself are containers, so this is directly comparable with
 *     {@code progress.totalTasks} rather than counting a different tier. A finished Epic reports
 *     0 even though nothing blocks it. The predicate is {@code
 *     EpicReadinessAssembler#isStartable}, shared with the Autopilot's ready frontier — what this
 *     advertises is exactly what the Autopilot would pick up.
 * @param milestone The Epic's assigned Milestone (release grouping), or {@code null} if unassigned.
 */
public record EpicResponse(
        UUID id,
        String title,
        String description,
        String motivation,
        String stage,
        String priority,
        LocalDate targetDate,
        Progress progress,
        SoftwareProjectRef softwareProject,
        List<RepoRef> repos,
        Instant createdAt,
        Instant updatedAt,
        long readyItemCount,
        MilestoneRef milestone) {

    /**
     * Rollup completion figures derived from descendant Tasks — never stored.
     *
     * @param startedTasks descendant Tasks that have left {@code backlog} — the "has any work
     *     begun?" question, which {@code doneTasks} cannot answer and which the delete guard needs.
     */
    public record Progress(long totalTasks, long doneTasks, long startedTasks) {}
}
