package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A single candidate Epic proposed by the Roadmap Provisioner analyzer (Decision 1), or as edited
 * by a reviewer before approval (Decision 4). Shared shape between the analyzer's
 * {@code roadmap_candidates.json} artifact and {@code SignalRequest.editedCandidates} — both must
 * conform to this record.
 *
 * <p>{@code repos}/{@code priority} are reviewer-context-only fields: shown in the gate's breakdown
 * editor as decomposition rationale, but never persisted — {@code InternalCreateEpicRequest} (the
 * DTO {@code RoadmapCandidateMaterializer} actually writes through) has no equivalent fields, and a
 * materialized Epic's {@code repos} is always derived from its software project, same as any
 * hand-created Epic (see Caveat 6).
 *
 * <p>{@code stories} needs {@code @Valid} (not just {@code @Size}) so that cascading bean
 * validation reaches each {@link CandidateStoryProposal}'s own {@code tasks @Size(max = 8)} —
 * without it, only this Story-count cap would be enforced when {@code SignalRequest.editedCandidates}
 * is validated (its own {@code @Valid} only cascades one level, into each {@code CandidateEpicProposal}).
 */
public record CandidateEpicProposal(
        @NotBlank @Size(max = 255) String title,
        String description,
        String motivation,
        List<String> repos,
        String priority,
        @Valid @Size(max = 8) List<CandidateStoryProposal> stories) {}
