package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Internal (agent-facing) request body for creating a Story under an Epic. */
public record InternalCreateStoryRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description) {}
