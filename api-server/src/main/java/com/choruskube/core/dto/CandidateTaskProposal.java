package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A single candidate Task within a {@link CandidateStoryProposal}, as proposed by the Roadmap
 * Provisioner analyzer (or edited by a reviewer) before materialization (Decision 1/5).
 */
public record CandidateTaskProposal(
        @NotBlank @Size(max = 255) String title, String description) {}
