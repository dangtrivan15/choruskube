package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Full PUT edit body for a Milestone. Deliberately carries no {@code softwareProjectId} — a
 * Milestone's owning project is fixed at create time.
 */
public record MilestoneUpdateRequest(
        @NotBlank @Size(max = 255) String name, String description, LocalDate targetDate) {}
