package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A single candidate Epic proposed by the Roadmap Provisioner analyzer, or as edited
 * by a reviewer before approval. Shared shape between the analyzer's
 * {@code roadmap_candidates.json} artifact and {@code SignalRequest.editedCandidates} — both must
 * conform to this record.
 *
 * <p>{@code repos} is a reviewer-context-only field: shown in the gate's breakdown editor as
 * decomposition rationale, but never persisted — {@code InternalCreateEpicRequest} (the DTO {@code
 * RoadmapCandidateMaterializer} actually writes through) has no {@code repos} field, and a
 * materialized Epic's {@code repos} is always derived from its software project, same as any
 * hand-created Epic. {@code priority} (free-text {@code High}/{@code Medium}/{@code
 * Low}), by contrast, IS persisted: {@code RoadmapCandidateMaterializer} parses it onto the
 * materialized Epic's initial (human-editable) {@code Priority}, defaulting to {@code medium} when
 * blank/unrecognized.
 *
 * <p>{@code stories} needs {@code @Valid} (not just {@code @Size}) so that cascading bean
 * validation reaches each {@link CandidateStoryProposal}'s own {@code tasks @Size(max = 8)} —
 * without it, only this Story-count cap would be enforced when {@code SignalRequest.editedCandidates}
 * is validated (its own {@code @Valid} only cascades one level, into each {@code CandidateEpicProposal}).
 *
 * <p>{@code key} is an optional author-assigned, artifact-local identifier — unique
 * within the artifact — that {@link CandidateDependency} and other items may reference; it is never
 * persisted. {@code milestone} is an optional reference to a {@link CandidateMilestone#key()}:
 * the materialized Epic's {@code milestoneId} is set to whichever Milestone
 * that key resolved (or reused) to.
 */
public record CandidateEpicProposal(
        @NotBlank @Size(max = 255) String title,
        String description,
        String motivation,
        List<String> repos,
        String priority,
        @Valid @Size(max = 8) List<CandidateStoryProposal> stories,
        String key,
        String milestone) {}
