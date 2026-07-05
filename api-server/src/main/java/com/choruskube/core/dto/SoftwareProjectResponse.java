package com.choruskube.core.dto;

import com.choruskube.core.model.RuntimeRequirements;
import java.time.Instant;
import java.util.UUID;

public record SoftwareProjectResponse(
        UUID id,
        String name,
        String type,
        String agentImage,
        String description,
        RuntimeRequirements runtimeRequirements,
        Instant createdAt,
        Instant updatedAt) {}
