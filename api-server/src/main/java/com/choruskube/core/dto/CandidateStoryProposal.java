package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A single candidate Story within a {@link CandidateEpicProposal}, carrying a variable-depth list
 * of candidate Tasks (Decision 5). {@code tasks} is capped at 8 (Caveat 4) and cascades validation
 * (via {@code @Valid}) into each {@link CandidateTaskProposal} — this only takes effect when a
 * {@link CandidateEpicProposal} validates its own {@code stories} list with {@code @Valid} too, so
 * the cascade reaches two levels deep from {@code SignalRequest.editedCandidates}.
 */
public record CandidateStoryProposal(
        @NotBlank @Size(max = 255) String title,
        String description,
        @Valid @Size(max = 8) List<CandidateTaskProposal> tasks) {}
