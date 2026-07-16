package com.choruskube.core.dto;

import java.time.Instant;
import java.util.UUID;

public record StoryResponse(
        UUID id,
        UUID epicId,
        String title,
        String description,
        String status,
        EpicResponse.Progress progress,
        Instant createdAt,
        Instant updatedAt) {}
