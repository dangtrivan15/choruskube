package com.choruskube.core.service;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.MaterializationSummary;
import java.util.List;
import java.util.UUID;

/**
 * Deterministically turns a reviewed Roadmap Provisioner candidate breakdown into real Epic/Story/
 * Task rows (Decision 3) — the same write path ({@code InternalRunService.createEpic/createStory/
 * createTask}) a human uses, so a materialized Epic is indistinguishable from a hand-created one.
 */
public interface RoadmapCandidateMaterializer {

    /**
     * @param runId the Roadmap Provisioner run whose {@code software_project_id} input resolves the
     *     target for every created Epic (see {@code InternalRunService#createEpic})
     * @param candidates the reviewed (possibly reviewer-edited) candidate breakdown
     * @return a summary of what was created and what was skipped
     */
    MaterializationSummary materialize(UUID runId, List<CandidateEpicProposal> candidates);
}
