package com.choruskube.core.dto;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

public record StorySpecVersionSummary(
        int version, Instant approvedAt, @Nullable UUID runId) {}
