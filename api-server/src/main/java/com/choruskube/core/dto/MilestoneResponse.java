package com.choruskube.core.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param epicCount Count of Epics currently tagged with this Milestone, computed at read time from
 *     {@code EpicRepository} (single-Milestone reads via {@code countByMilestoneId}, list pages via
 *     the batched {@code findByMilestoneIdIn} grouping) — never persisted.
 * @param progress Rollup of every descendant Task under this Milestone (across all its Epics'
 *     Stories), bucketed by board lane — computed at read time from a batched Epic → Story → Task
 *     walk ({@code DefaultMilestoneService}'s per-Milestone aggregate), never persisted.
 * @param atRisk {@code true} iff this Milestone's own {@code targetDate} is strictly before today
 *     (per the injected {@code Clock}) AND at least one Epic tagged with it is incomplete (its
 *     {@code RollupCalculator#effectiveStatus} is not {@code done}). A Milestone with no Epics, or
 *     no {@code targetDate}, is never at risk.
 * @param atRiskItemCount Count of this Milestone's own Epics and their Stories that are themselves
 *     individually at risk — {@code targetDate} strictly before today AND {@code effectiveStatus}
 *     not {@code done} — regardless of the Milestone-level {@link #atRisk} verdict above. Drill-down
 *     detail for this count is served by {@code GET /milestones/{id}/at-risk-items}.
 */
public record MilestoneResponse(
        UUID id,
        String name,
        String description,
        UUID softwareProjectId,
        LocalDate targetDate,
        long epicCount,
        Progress progress,
        boolean atRisk,
        long atRiskItemCount,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Task-count rollup bucketed by board lane, over every descendant Task of every Story of every
     * Epic tagged with this Milestone. {@code doneTasks} mirrors {@code RollupCalculator}'s notion
     * of "done"; {@code inProgressTasks} is the remainder of "started" Tasks that are not yet done;
     * {@code notStartedTasks} is everything still in {@code backlog}. {@code doneTasks +
     * inProgressTasks + notStartedTasks == totalTasks} always holds.
     */
    public record Progress(long totalTasks, long doneTasks, long inProgressTasks, long notStartedTasks) {}
}
