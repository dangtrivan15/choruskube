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

    /** Who performs the GitHub call for a {@code roadmap_extension} gate's newly-created Tasks. */
    enum IssueFiling {
        /** The API calls GitHub itself, as every gate did before the Worker took over filing. */
        ON_API,
        /** The API only resolves each Task's repo/title/body; the run's Worker files the issue. */
        BY_WORKER
    }

    /**
     * @param runId the run whose {@code software_project_id} input resolves the target for every
     *     created Epic/Milestone (see {@code InternalRunService#createEpic})
     * @param document the reviewed (possibly reviewer-edited) candidate document — milestones,
     *     Epics/Stories/Tasks (anchors included), and dependency edges
     * @param mode the gate's materialize mode — only {@code roadmap_extension} files a GitHub issue
     *     for each newly-created Task
     * @return a summary of what was created and what was skipped
     */
    default MaterializationSummary materialize(
            UUID runId, RoadmapCandidatesDocument document, RoadmapMaterializeMode mode) {
        return materialize(runId, document, mode, IssueFiling.ON_API);
    }

    /**
     * @param filing for a {@code roadmap_extension} gate, whether the issue for each newly-created
     *     Task is filed here (see {@code GitHubAppService#createIssue}) or only resolved and
     *     returned in the summary's {@code issueTargets} for the run's Worker to file. Ignored for
     *     any other mode, which files nothing either way
     */
    MaterializationSummary materialize(
            UUID runId, RoadmapCandidatesDocument document, RoadmapMaterializeMode mode, IssueFiling filing);

    /**
     * Materializes {@code document} through {@code writer}, which decides the target project and
     * the authority each row is created under. Never files GitHub issues — that belongs to a
     * {@code roadmap_extension} gate alone.
     */
    MaterializationSummary materialize(RoadmapItemWriter writer, RoadmapCandidatesDocument document);
}
