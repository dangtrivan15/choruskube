package com.choruskube.core.dto;

import java.util.UUID;

/** Lightweight Milestone reference embedded in an {@link EpicResponse}/{@link TimelineEpicSummary}. */
public record MilestoneRef(UUID id, String name) {}
