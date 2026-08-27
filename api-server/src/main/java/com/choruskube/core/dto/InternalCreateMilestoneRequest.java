package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * Internal (agent-facing) request body for creating a Milestone (Decision 6) — the
 * imperative-agent counterpart to {@code CandidateMilestone}. Scoped directly under the calling
 * run's resolved {@code software_project_id}; there is no cross-item ownership check to perform
 * (unlike {@link InternalCreateDependencyRequest}), since a Milestone is created fresh rather than
 * referencing an existing item.
 */
public record InternalCreateMilestoneRequest(@NotBlank String name, String description, LocalDate targetDate) {}
