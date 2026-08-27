package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The Roadmap Provisioner candidate artifact as a document (Decision 5), replacing the former bare
 * top-level array of {@link CandidateEpicProposal}. Shared shape between the analyzer's {@code
 * roadmap_candidates.json} artifact ({@code RoadmapCandidatesArtifactResolver}) and the reviewer's
 * (possibly edited) submission ({@code SignalRequest.editedCandidates}) — both conform to this
 * record. {@code RoadmapCandidatesArtifactResolver} also accepts a legacy bare JSON array, wrapping
 * it as {@code { epics: [...] }} for back-compat with artifacts written before this feature.
 *
 * <p>{@code milestones} and {@code dependencies} are capped at 32 — generous relative to the
 * existing {@code @Size(max = 8)} Epic/Story/Task caps, since a single flat list can span every
 * Epic in the artifact. {@code epics} keeps the pre-existing {@code @Size(max = 8)} cap.
 */
public record RoadmapCandidatesDocument(
        @Valid @Size(max = 32) List<CandidateMilestone> milestones,
        @Valid @Size(max = 8) List<CandidateEpicProposal> epics,
        @Valid @Size(max = 32) List<CandidateDependency> dependencies) {}
