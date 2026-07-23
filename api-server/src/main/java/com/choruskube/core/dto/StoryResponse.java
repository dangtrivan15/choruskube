package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Readiness;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code readiness} is {@code null} unless this response was assembled by the Roadmap Graph View
 * (Decision 2) — it is computed at read time from {@code work_item_dependency} edges, which
 * plain Story CRUD reads have no reason to join against.
 */
public record StoryResponse(
        UUID id,
        UUID epicId,
        String title,
        String description,
        String status,
        @Nullable Readiness readiness,
        EpicResponse.Progress progress,
        Instant createdAt,
        Instant updatedAt) {}
