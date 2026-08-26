package com.choruskube.core.service;

import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import java.util.UUID;

/**
 * Deterministically turns a reviewed Roadmap Provisioner candidate breakdown into real Milestone/
 * Epic/Story/Task rows plus dependency edges (Decision 3/4/5) — the same write paths ({@code
 * InternalRunService.createEpic/createStory/createTask}, {@code MilestoneService.findOrCreate},
 * {@code WorkItemDependencyService.create}) a human/agent uses, so a materialized item is
 * indistinguishable from a hand-created one.
 */
public interface RoadmapCandidateMaterializer {

    /**
     * @param runId the Roadmap Provisioner run whose {@code software_project_id} input resolves the
     *     target for every created Epic/Milestone (see {@code InternalRunService#createEpic})
     * @param document the reviewed (possibly reviewer-edited) candidate breakdown — milestones,
     *     Epics/Stories/Tasks, and dependency edges (Decision 5)
     * @return a summary of what was created and what was skipped
     */
    MaterializationSummary materialize(UUID runId, RoadmapCandidatesDocument document);
}
