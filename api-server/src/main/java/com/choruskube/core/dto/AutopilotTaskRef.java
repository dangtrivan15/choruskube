package com.choruskube.core.dto;

import java.util.UUID;

/**
 * {@code runId} is null in {@code nextUp} — nothing has started yet — and set in
 * {@code awaitingYou}/{@code needsAttention}. {@code status} follows it: the Task's own status
 * where there is no run, and the RUN's status where there is, because a parked run's Task reads
 * {@code in_progress} either way and the useful distinction is {@code awaiting_human} versus
 * {@code awaiting_retry}.
 *
 * <p>{@code kind} is {@link #KIND_TASK} or {@link #KIND_DESIGN_STORY}. A design item has no
 * {@code taskId}; {@code storyId} names the Story being designed, and its {@code status} is {@code
 * "design"} until a run exists.
 */
public record AutopilotTaskRef(UUID taskId, String title, UUID runId, String status, UUID storyId, String kind) {

    public static final String KIND_TASK = "task";
    public static final String KIND_DESIGN_STORY = "design_story";
}
