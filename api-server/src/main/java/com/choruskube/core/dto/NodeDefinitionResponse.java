package com.choruskube.core.dto;

import java.time.Instant;
import java.util.UUID;

public record NodeDefinitionResponse(
        UUID id,
        String name,
        String executorType,
        String image,
        String promptTemplate,
        String skills,
        String inputSpec,
        String outputSpec,
        int timeoutSeconds,
        String secrets,
        Instant createdAt,
        Instant updatedAt) {}
