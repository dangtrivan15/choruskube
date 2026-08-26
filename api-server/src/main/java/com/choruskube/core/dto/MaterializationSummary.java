package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of {@link com.choruskube.core.service.RoadmapCandidateMaterializer#materialize}.
 * Best-effort per candidate Epic (Decision 3, Caveat 3): a failure materializing one candidate is
 * recorded in {@code errors} rather than aborting the rest of the batch. Dependency-edge creation
 * (Decision 3) is likewise best-effort per edge: a cyclic or unresolvable edge is skipped and
 * recorded in {@code errors} rather than aborting the batch; {@code createdDependencyCount} counts
 * only the edges that were actually created. {@code createdMilestoneIds} lists every Milestone
 * created OR reused (find-or-create by name, Decision 4) during this materialization.
 */
public record MaterializationSummary(
        List<UUID> createdEpicIds, List<UUID> createdMilestoneIds, int createdDependencyCount, List<String> errors) {

    /** Count of top-level candidate Epics successfully materialized (unrelated to Milestones/edges). */
    public int materializedCount() {
        return createdEpicIds.size();
    }

    public int skippedCount() {
        return errors.size();
    }
}
