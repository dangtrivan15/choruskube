package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * Best-effort per candidate Epic: a failure materializing one candidate is
 * recorded in {@code errors} rather than aborting the rest of the batch. Dependency-edge creation
 * is likewise best-effort per edge: a cyclic or unresolvable edge is skipped and
 * recorded in {@code errors} rather than aborting the batch; {@code createdDependencyCount} counts
 * only the edges that were actually created. {@code createdMilestoneIds} lists every Milestone
 * created OR reused (find-or-create by name) during this materialization.
 */
public record MaterializationSummary(
        List<UUID> createdEpicIds, List<UUID> createdMilestoneIds, int createdDependencyCount, List<String> errors) {

    public int materializedCount() {
        return createdEpicIds.size();
    }

    public int skippedCount() {
        return errors.size();
    }
}
