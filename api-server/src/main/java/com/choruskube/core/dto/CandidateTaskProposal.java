package com.choruskube.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * {@code key} is an optional author-assigned, artifact-local identifier, same
 * convention as {@link CandidateEpicProposal#key()}. {@code priority} (free-text {@code High}/
 * {@code Medium}/{@code Low}) is parsed onto the materialized Task's initial {@code
 * Priority}, defaulting to {@code medium} when blank/unrecognized — same as Epic/Story priority.
 *
 * <p>{@code existingId} anchors this entry to an already-materialized Task, the same convention
 * as {@link CandidateEpicProposal#existingId()}. {@code repoId} names which of the run's project's
 * {@code GitRepo}s a NEW Task is about — optional when the project resolves to exactly one repo,
 * required and project-checked otherwise (see {@code RoadmapProposalValidator}) — and is used to
 * target the automatically-filed GitHub issue.
 */
public record CandidateTaskProposal(
        @Size(max = 255) String title, String description, String key, String priority, UUID existingId, UUID repoId) {

    @JsonIgnore
    @AssertTrue(message = "title is required for a new item")
    public boolean isTitleProvidedOrExisting() {
        return existingId != null || (title != null && !title.isBlank());
    }
}
