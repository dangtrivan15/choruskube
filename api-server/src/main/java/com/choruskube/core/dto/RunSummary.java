package com.choruskube.core.dto;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

public record RunSummary(
        UUID id,
        UUID graphTemplateId,
        String templateName,
        String name,
        String status,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        @Nullable SoftwareProjectRef softwareProject) {}
