package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * Best-effort per candidate Epic: a failure materializing one candidate is
 * recorded in {@code errors} rather than aborting the rest of the batch. Dependency-edge creation
 * is likewise best-effort per edge: a cyclic or unresolvable edge is skipped and
 * recorded in {@code errors} rather than aborting the batch; {@code createdDependencyCount} counts
 * only the edges that were actually created. {@code createdMilestoneIds} lists every Milestone
 * created OR reused (find-or-create by name) during this materialization. {@code issueTargets}
 * is populated only by {@code DefaultRoadmapCandidateMaterializer}'s {@code BY_WORKER} filing
 * mode: one entry per new Task whose issue the run's Worker, not the API, is asked to file.
 */
public record MaterializationSummary(
        List<UUID> createdEpicIds,
        List<UUID> createdStoryIds,
        List<UUID> createdTaskIds,
        List<UUID> createdMilestoneIds,
        int createdDependencyCount,
        List<String> errors,
        List<TaskIssueTarget> issueTargets) {

    /** Kept so every pre-existing caller/test compiles unchanged against a filing-less summary. */
    public MaterializationSummary(
            List<UUID> createdEpicIds,
            List<UUID> createdStoryIds,
            List<UUID> createdTaskIds,
            List<UUID> createdMilestoneIds,
            int createdDependencyCount,
            List<String> errors) {
        this(
                createdEpicIds,
                createdStoryIds,
                createdTaskIds,
                createdMilestoneIds,
                createdDependencyCount,
                errors,
                List.of());
    }

    public int materializedCount() {
        return createdEpicIds.size();
    }

    public int skippedCount() {
        return errors.size();
    }
}
