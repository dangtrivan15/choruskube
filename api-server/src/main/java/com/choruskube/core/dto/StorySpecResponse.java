package com.choruskube.core.dto;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

public record StorySpecResponse(
        UUID storyId,
        int version,
        String content,
        Instant approvedAt,
        @Nullable UUID runId) {}
