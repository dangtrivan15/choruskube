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
 * <p>{@code feedback}'s {@code @Size} mirrors the web-ui's {@code MAX_GATE_FEEDBACK_LENGTH}
 * (decisions.ts) and is enforced only by the controller's {@code @Valid} — a request this DTO's
 * constructor builds for itself (a Story-version re-check, a Story-conflict release) is never run
 * through a {@code Validator} and may exceed it.
 */
public record SignalRequest(
        @NotBlank String decision,
        @Size(max = 20000) String feedback,
        String attachmentRefs,
        @Valid RoadmapCandidatesDocument editedCandidates) {}
