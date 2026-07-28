package com.choruskube.core.dto;

import jakarta.annotation.Nullable;
import java.util.UUID;

public record RunTaskSummary(
        UUID id,
        String title,
        String status,
        @Nullable SoftwareProjectRef softwareProject,
        @Nullable UUID storyId,
        @Nullable String storyTitle,
        @Nullable UUID epicId,
        @Nullable String epicTitle) {}
