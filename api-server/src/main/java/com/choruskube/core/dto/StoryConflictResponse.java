package com.choruskube.core.dto;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

public record StoryConflictResponse(
        UUID id,
        UUID taskRunId,
        @Nullable UUID taskId,
        @Nullable String taskTitle,
        int raisedAgainstVersion,
        @Nullable UUID revisionRunId,
        Instant createdAt) {}
