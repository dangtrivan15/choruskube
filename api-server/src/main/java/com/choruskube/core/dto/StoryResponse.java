package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Readiness;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code readiness} is populated by the Roadmap Graph View and by {@code list()} —
 * it is computed at read time from {@code work_item_dependency} edges. Single-item reads
 * (create/get/update) leave it {@code null}, since they have no reason to join dependency edges
 * just to return the one item just written.
 *
 * <p>{@code stage} is the persisted Story board column, mirroring {@code EpicResponse#stage}
 * exactly.
 *
 * <p>{@code readyTaskCount} is how many of this Story's Tasks can be started right now — still
 * {@code backlog} and unblocked, per {@code EpicReadinessAssembler#isStartable}. It is the Story
 * tier's counterpart to {@code EpicResponse#readyItemCount} and exists because {@code readiness}
 * alone cannot answer "is there work to pick up here": READY is a statement about this Story's
 * *dependencies*, so a Story whose Tasks are all finished is still READY. It is populated on
 * exactly the paths {@code readiness} is, and is {@code null} on the single-item reads for the
 * same reason.
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
        @Nullable Long readyTaskCount,
        EpicResponse.Progress progress,
        Instant createdAt,
        Instant updatedAt) {}
