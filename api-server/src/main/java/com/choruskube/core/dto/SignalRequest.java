package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code editedCandidates} carries the reviewer's (possibly edited) Roadmap Provisioner candidate
 * breakdown (Decision 4) — transient, never persisted as its own row; consumed by
 * {@code RoadmapCandidateMaterializer} on an "approved" decision and discarded otherwise.
 * {@code @Valid} cascades into {@link RoadmapCandidatesDocument} (Decision 5), which in turn
 * cascades into every {@link CandidateMilestone}/{@link CandidateEpicProposal}/{@link
 * CandidateDependency}.
 */
public record SignalRequest(
        @NotBlank String decision,
        String feedback,
        String attachmentRefs,
        @Valid RoadmapCandidatesDocument editedCandidates) {}
