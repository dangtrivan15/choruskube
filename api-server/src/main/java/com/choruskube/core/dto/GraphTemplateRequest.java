package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;

public record GraphTemplateRequest(
        @NotBlank String name, String description, String graphId, Integer version, String inputSchema) {}
