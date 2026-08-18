package com.choruskube.core.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @param readyItemCount Count of this Epic's descendant Stories/Tasks with {@code
 *     Readiness.READY} (roadmap "ready to start" filter, Decision 2 of that feature) — computed
 *     at read time from the same {@code EpicReadinessAssembler} walk the Story/Task list
 *     endpoints use, never persisted.
 * @param milestone The Epic's assigned Milestone (release grouping), or {@code null} if unassigned
 *     (Decision 2/4 of the "Group Epics under a named Milestone / Release" feature). Populated on
 *     both the single-Epic {@code toResponse} path and the batched multi-Epic {@code toResponses}
 *     path — see {@code DefaultEpicService}.
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
     * Rollup completion figures derived from descendant Tasks (Decision 2) — never stored. These
     * counts are the whole of what an Epic reports about completion: there is no synthesized
     * status field, because an Epic has no {@code done} lane to be in and a rollup that claimed
     * one would contradict {@link #stage}. Render "{@code doneTasks} of {@code totalTasks} tasks
     * done" and let {@code stage} say where the item sits.
     *
     * @param startedTasks descendant Tasks that have left {@code backlog} — the "has any work
     *     begun?" question, which {@code doneTasks} cannot answer and which the delete guard needs.
     */
    public record Progress(long totalTasks, long doneTasks, long startedTasks) {}
}
