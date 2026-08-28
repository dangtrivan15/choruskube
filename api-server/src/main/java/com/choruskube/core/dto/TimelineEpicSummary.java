package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One Epic lane on the Roadmap Timeline View, carrying its own Stories. {@code stories} is
 * ordered ascending by {@code createdAt} — an empty Epic gets an empty list rather than being
 * omitted.
 *
 * <p>{@code stalled} is the same 14-day in-progress-staleness signal as {@link
 * TimelineStorySummary#stalled()}, computed from this Epic's own {@code stage}/{@code updatedAt}
 * — it does NOT aggregate its Stories' {@code stalled} flags.
 *
 * <p>{@code milestone} is the Epic's assigned Milestone reference, or {@code null} if unassigned —
 * populated by {@code DefaultRoadmapTimelineService}, mirroring {@code EpicResponse#milestone}.
 */
public record TimelineEpicSummary(
        UUID id,
        String title,
        String stage,
        String priority,
        Instant createdAt,
        Instant updatedAt,
        List<TimelineStorySummary> stories,
        boolean stalled,
        MilestoneRef milestone) {}
