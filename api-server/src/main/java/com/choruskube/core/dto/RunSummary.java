package com.choruskube.core.dto;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/**
 * @param autopilotId set when the Autopilot started this run, null when a person did
 * @param storySpecVersion the Story spec version a Task Delivery run planned against; null on other runs
 */
public record RunSummary(
        UUID id,
        UUID graphTemplateId,
        String templateName,
        String name,
        String status,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        @Nullable UUID autopilotId,
        @Nullable SoftwareProjectRef softwareProject,
        @Nullable Integer storySpecVersion) {}
