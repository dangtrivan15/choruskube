package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code editedCandidates} carries the reviewer's (possibly edited) Roadmap Provisioner candidate
 * breakdown — transient, never persisted as its own row; consumed by
 * {@code RoadmapCandidateMaterializer} on an "approved" decision and discarded otherwise.
 * {@code @Valid} cascades into {@link RoadmapCandidatesDocument}, which in turn
 * cascades into every {@link CandidateMilestone}/{@link CandidateEpicProposal}/{@link
 * CandidateDependency}.
 *
 * <p>{@code feedback}'s {@code @Size} must equal the web-ui's {@code MAX_GATE_FEEDBACK_LENGTH}
 * (decisions.ts). Only the signal controller's {@code @Valid} applies it: a request the server builds
 * itself (a Story-version re-check, a Story-conflict release) can exceed it and must not be validated.
 */
public record SignalRequest(
        @NotBlank String decision,
        @Size(max = 20000) String feedback,
        String attachmentRefs,
        @Valid RoadmapCandidatesDocument editedCandidates) {}
