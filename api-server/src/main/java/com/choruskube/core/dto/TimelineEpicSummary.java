package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One Epic lane on the Roadmap Timeline View, carrying its own Stories (Decision 1/2 of that
 * feature — activity source is the existing {@code createdAt}/{@code updatedAt} audit columns,
 * not a new history table). {@code stage} is {@code epic.getStage().name()}, mirroring {@link
 * TimelineStorySummary#stage()}. {@code stories} is ordered ascending by {@code createdAt} —
 * populated by {@code DefaultRoadmapTimelineService}, an empty Epic gets an empty list rather
 * than being omitted.
 *
 * <p>{@code stalled} is the same 14-day in-progress-staleness signal as {@link
 * TimelineStorySummary#stalled()}, computed from this Epic's own {@code stage}/{@code updatedAt}
 * — it does NOT aggregate its Stories' {@code stalled} flags (that aggregation, if wanted, is a
 * UI-layer concern). There is no Epic-level {@code readiness} field: readiness is a dependency-
 * graph concept and Epics do not participate in the Story/Task dependency graph.
 */
public record TimelineEpicSummary(
        UUID id,
        String title,
        String stage,
        Instant createdAt,
        Instant updatedAt,
        List<TimelineStorySummary> stories,
        boolean stalled) {}
