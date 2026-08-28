package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code key} is an optional author-assigned, artifact-local identifier, same
 * convention as {@link CandidateEpicProposal#key()}. {@code priority} (free-text {@code High}/
 * {@code Medium}/{@code Low}) is parsed onto the materialized Task's initial {@code
 * Priority}, defaulting to {@code medium} when blank/unrecognized — same as Epic/Story priority.
 */
public record CandidateTaskProposal(
        @NotBlank @Size(max = 255) String title, String description, String key, String priority) {}
