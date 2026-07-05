package com.choruskube.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record GraphTemplateResponse(
        UUID id,
        String graphId,
        Integer version,
        String name,
        String description,
        JsonNode inputSchema,
        boolean system,
        Instant createdAt,
        Instant updatedAt) {}
