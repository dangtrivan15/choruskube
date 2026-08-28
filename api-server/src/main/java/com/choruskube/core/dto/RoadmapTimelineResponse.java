package com.choruskube.core.dto;

import java.util.List;

/**
 * Full org roadmap for the Timeline View: every scoped Epic (with its Stories nested) in one
 * unpaginated response — matches the existing per-epic Roadmap Graph endpoint's precedent.
 * Backs {@code GET /api/v1/roadmap/timeline}.
 */
public record RoadmapTimelineResponse(List<TimelineEpicSummary> epics) {}
