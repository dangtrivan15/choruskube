package com.choruskube.core.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record NodeDefinitionRequest(
        @NotBlank String name,
        @NotBlank String executorType,
        String image,
        String promptTemplate,
        String skills,
        String inputSpec,
        String outputSpec,

        @Min(value = 60, message = "timeoutSeconds must be at least 60 (1 minute)")
        @Max(value = 86400, message = "timeoutSeconds must be at most 86400 (24 hours)")
        Integer timeoutSeconds,

        String secrets) {}
