package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * Internal (agent-facing) request body for creating a Milestone, scoped directly under the
 * calling run's resolved {@code software_project_id}.
 */
public record InternalCreateMilestoneRequest(@NotBlank String name, String description, LocalDate targetDate) {}
