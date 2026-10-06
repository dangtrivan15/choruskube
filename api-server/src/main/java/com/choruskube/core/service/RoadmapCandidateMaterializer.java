package com.choruskube.core.service;

import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import java.util.UUID;

/**
 * Deterministically turns a reviewed roadmap candidate document into real Milestone/Epic/Story/
 * Task rows plus dependency edges — the same write paths ({@code
 * InternalRunService.createEpic/createStory/createTask/createDependency}, {@code
 * MilestoneService.findOrCreate}) a human/agent uses, so a materialized item is indistinguishable
 * from a hand-created one. An {@code existingId} anchor is never created — only its key (if any)
 * joins the dependency-resolution map, and its children (if any) attach under it.
 */
public interface RoadmapCandidateMaterializer {

    /**
     * @param runId the run whose {@code software_project_id} input resolves the target for every
     *     created Epic/Milestone (see {@code InternalRunService#createEpic})
     * @param document the reviewed (possibly reviewer-edited) candidate document — milestones,
     *     Epics/Stories/Tasks (anchors included), and dependency edges
     * @param mode the gate's materialize mode — only {@code roadmap_extension} files a GitHub issue
     *     for each newly-created Task (see {@code GitHubAppService#createIssue})
     * @return a summary of what was created and what was skipped
     */
    MaterializationSummary materialize(UUID runId, RoadmapCandidatesDocument document, RoadmapMaterializeMode mode);

    /**
     * Materializes {@code document} through {@code writer}, which decides the target project and
     * the authority each row is created under. Never files GitHub issues — that belongs to a
     * {@code roadmap_extension} gate alone.
     */
    MaterializationSummary materialize(RoadmapItemWriter writer, RoadmapCandidatesDocument document);
}
