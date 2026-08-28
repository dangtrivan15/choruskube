package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Shared shape between the analyzer's {@code roadmap_candidates.json} artifact ({@code
 * RoadmapCandidatesArtifactResolver}) and the reviewer's (possibly edited) submission ({@code
 * SignalRequest.editedCandidates}) — both conform to this record. {@code
 * RoadmapCandidatesArtifactResolver} also accepts a legacy bare JSON array, wrapping it as
 * {@code { epics: [...] }} for back-compat with artifacts written before this feature.
 */
public record RoadmapCandidatesDocument(
        @Valid @Size(max = 32) List<CandidateMilestone> milestones,
        @Valid @Size(max = 8) List<CandidateEpicProposal> epics,
        @Valid @Size(max = 32) List<CandidateDependency> dependencies) {}
