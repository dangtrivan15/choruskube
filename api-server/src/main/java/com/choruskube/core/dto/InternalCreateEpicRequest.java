package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Internal (agent-facing) request body for creating an Epic. The Epic's target is resolved
 * from the run's {@code software_project_id} input; agents do not pick a target directly — the
 * run's project IS the Epic's project.
 */
public record InternalCreateEpicRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description, String motivation) {}
