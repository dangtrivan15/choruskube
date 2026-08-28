package com.choruskube.core.dto;

import java.util.UUID;

/**
 * {@code runId} is null in {@code nextUp} — nothing has started yet — and set in
 * {@code awaitingYou}/{@code needsAttention}. {@code status} follows it: the Task's own status
 * where there is no run, and the RUN's status where there is, because a parked run's Task reads
 * {@code in_progress} either way and the useful distinction is {@code awaiting_human} versus
 * {@code live_chat} versus {@code awaiting_retry}.
 */
public record AutopilotTaskRef(UUID taskId, String title, UUID runId, String status) {}
