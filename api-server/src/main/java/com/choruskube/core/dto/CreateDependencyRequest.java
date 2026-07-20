package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateDependencyRequest(
        @NotBlank String blockingItemType,
        @NotNull UUID blockingItemId,
        @NotBlank String blockedItemType,
        @NotNull UUID blockedItemId) {}
