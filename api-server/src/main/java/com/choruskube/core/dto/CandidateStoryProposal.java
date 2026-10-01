package com.choruskube.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * A single candidate Story within a {@link CandidateEpicProposal}, carrying a variable-depth list
 * of candidate Tasks. {@code tasks} is capped at 8 and cascades validation
 * (via {@code @Valid}) into each {@link CandidateTaskProposal} — this only takes effect when a
 * {@link CandidateEpicProposal} validates its own {@code stories} list with {@code @Valid} too, so
 * the cascade reaches two levels deep from {@code SignalRequest.editedCandidates}.
 *
 * <p>{@code key} is an optional author-assigned, artifact-local identifier, same
 * convention as {@link CandidateEpicProposal#key()}. {@code priority} (free-text {@code High}/
 * {@code Medium}/{@code Low}) is parsed onto the materialized Story's initial {@code
 * Priority}, defaulting to {@code medium} when blank/unrecognized — same as Epic priority.
 *
 * <p>{@code existingId} anchors this entry to an already-materialized Story, the same convention
 * as {@link CandidateEpicProposal#existingId()}.
 */
public record CandidateStoryProposal(
        @Size(max = 255) String title,
        String description,
        @Valid @Size(max = 8) List<CandidateTaskProposal> tasks,
        String key,
        String priority,
        UUID existingId) {

    @JsonIgnore
    @AssertTrue(message = "title is required for a new item")
    public boolean isTitleProvidedOrExisting() {
        return existingId != null || (title != null && !title.isBlank());
    }
}
