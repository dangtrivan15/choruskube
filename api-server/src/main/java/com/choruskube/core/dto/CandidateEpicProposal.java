package com.choruskube.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Shared shape between the analyzer's {@code roadmap_candidates.json} artifact and
 * {@code SignalRequest.editedCandidates} — both must conform to this record.
 *
 * <p>{@code repos} is a reviewer-context-only field: shown in the gate's breakdown editor as
 * decomposition rationale, but never persisted. {@code priority} (free-text {@code High}/{@code
 * Medium}/{@code Low}) IS persisted: {@code RoadmapCandidateMaterializer} parses it onto the
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
 *
 * <p>{@code existingId} turns this entry into an anchor to an already-materialized Epic: nothing
 * is created for it, its other fields (besides {@code key} and nested children) are ignored, and
 * {@code title} is not required. See {@code RoadmapAnchorLookup} for how an anchor is resolved.
 */
public record CandidateEpicProposal(
        @Size(max = 255) String title,
        String description,
        String motivation,
        List<String> repos,
        String priority,
        @Valid @Size(max = 8) List<CandidateStoryProposal> stories,
        String key,
        String milestone,
        UUID existingId) {

    /** A new (non-anchor) entry must still have a title; an anchor's title is ignored. */
    @JsonIgnore
    @AssertTrue(message = "title is required for a new item")
    public boolean isTitleProvidedOrExisting() {
        return existingId != null || (title != null && !title.isBlank());
    }
}
