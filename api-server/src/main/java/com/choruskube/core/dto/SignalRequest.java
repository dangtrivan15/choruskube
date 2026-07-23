package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code editedCandidates} carries the reviewer's (possibly edited) Roadmap Provisioner candidate
 * breakdown (Decision 4) — transient, never persisted as its own row; consumed by
 * {@code RoadmapCandidateMaterializer} on an "approved" decision and discarded otherwise.
 * {@code @Valid} cascades into each {@link CandidateEpicProposal}.
 */
public record SignalRequest(
        @NotBlank String decision,
        String feedback,
        String attachmentRefs,
        @Valid @Size(max = 8) List<CandidateEpicProposal> editedCandidates) {}
