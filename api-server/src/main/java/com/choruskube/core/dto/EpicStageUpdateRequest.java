package com.choruskube.core.dto;

import com.choruskube.core.model.enums.WorkItemStatus;
import jakarta.validation.constraints.NotNull;

public record EpicStageUpdateRequest(@NotNull WorkItemStatus stage) {}
