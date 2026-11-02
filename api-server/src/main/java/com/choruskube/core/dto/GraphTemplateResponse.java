package com.choruskube.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
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
        Instant updatedAt,
        // Non-null for a template only a Story or a Task starts: the run launcher lists it disabled
        // with this sentence, and a start through POST /api/v1/runs is refused with it.
        @Nullable String standaloneStartRefusal) {}
