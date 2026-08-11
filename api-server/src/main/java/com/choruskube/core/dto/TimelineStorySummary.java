package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Readiness;
import java.time.Instant;
import java.util.UUID;

/**
 * One Story plotted on the Roadmap Timeline View, nested under its owning {@link
 * TimelineEpicSummary}. {@code stage} is {@code story.getStage().name()} — a {@code String}
 * exposed on the response DTO rather than the persisted {@code WorkItemStatus} enum, matching
 * every existing {@code *Response} record's convention (raw {@code WorkItemStatus} is reserved
 * for request DTOs needing {@code @NotNull} validation).
 *
 * <p>{@code readiness} reuses the exact same per-Epic dependency computation the Roadmap Graph
 * endpoint uses ({@code EpicReadinessAssembler}) — a Story blocked there is blocked here too, no
 * independent readiness logic. {@code stalled} is a separate, Timeline-only signal derived
 * server-side by {@code DefaultRoadmapTimelineService}: {@code true} iff the Story's stage is
 * {@code in_progress} and its {@code updatedAt} is more than 14 days in the past — a proxy for
 * "in flight but no recent activity", independent of {@code readiness}.
 */
public record TimelineStorySummary(
        UUID id,
        UUID epicId,
        String title,
        String stage,
        Instant createdAt,
        Instant updatedAt,
        Readiness readiness,
        boolean stalled) {}
