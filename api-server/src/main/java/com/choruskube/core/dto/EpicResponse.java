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
        String status,
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

    /** Rollup completion figure derived from descendant Tasks (Decision 2) — never stored. */
    public record Progress(long totalTasks, long doneTasks) {}
}
