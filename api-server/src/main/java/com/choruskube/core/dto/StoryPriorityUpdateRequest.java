package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Priority;
import jakarta.validation.constraints.NotNull;

public record StoryPriorityUpdateRequest(@NotNull Priority priority) {}
