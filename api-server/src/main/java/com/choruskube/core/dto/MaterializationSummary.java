package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of {@link com.choruskube.core.service.RoadmapCandidateMaterializer#materialize}.
 * Best-effort per candidate Epic (Decision 3, Caveat 3): a failure materializing one candidate is
 * recorded in {@code errors} rather than aborting the rest of the batch.
 */
public record MaterializationSummary(List<UUID> createdEpicIds, List<String> errors) {

    public int materializedCount() {
        return createdEpicIds.size();
    }

    public int skippedCount() {
        return errors.size();
    }
}
