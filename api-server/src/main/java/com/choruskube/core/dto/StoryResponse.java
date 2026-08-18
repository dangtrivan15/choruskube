package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Readiness;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code readiness} is populated by the Roadmap Graph View and by {@code list()} (Decision 1/2) —
 * it is computed at read time from {@code work_item_dependency} edges. Single-item reads
 * (create/get/update) leave it {@code null}, since they have no reason to join dependency edges
 * just to return the one item just written.
 *
 * <p>{@code stage} is the persisted Story board column, mirroring {@code EpicResponse#stage}
 * exactly. It is the only statement this DTO makes about where the Story sits; completion is
 * reported as the {@code progress} counts, never as a synthesized status (see {@code
 * RollupCalculator}).
 */
public record StoryResponse(
        UUID id,
        UUID epicId,
        String title,
        String description,
        String stage,
        String priority,
        LocalDate targetDate,
        @Nullable Readiness readiness,
        EpicResponse.Progress progress,
        Instant createdAt,
        Instant updatedAt) {}
