package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Internal (agent-facing) request body for creating a feature proposal. The proposal target
 * is resolved from the run's {@code software_project_id} input; agents do not pick a target
 * directly — the run's project IS the proposal's project.
 */
public record InternalCreateFeatureProposalRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description, String motivation) {}
