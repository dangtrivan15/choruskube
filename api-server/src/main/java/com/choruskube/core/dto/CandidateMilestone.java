package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * A single candidate Milestone proposed by the Roadmap Provisioner analyzer, or as
 * edited by a reviewer before approval. {@code key} is an optional author-assigned, artifact-local
 * identifier that {@link CandidateEpicProposal#milestone()} references — it is never
 * persisted itself; {@code RoadmapCandidateMaterializer} resolves it to the milestone actually
 * created (or reused, via find-or-create by name) and maps {@code key -> milestoneId}.
 */
public record CandidateMilestone(
        String key, @NotBlank @Size(max = 255) String name, String description, LocalDate targetDate) {}
