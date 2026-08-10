package com.choruskube.core.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One Story plotted on the Roadmap Timeline View, nested under its owning {@link
 * TimelineEpicSummary}. {@code stage} is {@code story.getStage().name()} — a {@code String}
 * exposed on the response DTO rather than the persisted {@code WorkItemStatus} enum, matching
 * every existing {@code *Response} record's convention (raw {@code WorkItemStatus} is reserved
 * for request DTOs needing {@code @NotNull} validation).
 */
public record TimelineStorySummary(
        UUID id, UUID epicId, String title, String stage, Instant createdAt, Instant updatedAt) {}
