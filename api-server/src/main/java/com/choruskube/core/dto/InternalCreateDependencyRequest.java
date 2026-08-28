package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Internal (agent-facing) request body for creating a "blocking" dependency edge between two
 * items already resolved to real database ids — the imperative-agent counterpart to
 * {@code CandidateDependency}, which references pre-materialization candidate keys instead.
 * {@code InternalRunService.createDependency} validates both endpoints belong to the calling run's
 * software project before delegating to the same {@code WorkItemDependencyService.create} the
 * Roadmap Candidate Materializer uses.
 */
public record InternalCreateDependencyRequest(
        @NotBlank String blockingItemType,
        @NotNull UUID blockingItemId,
        @NotBlank String blockedItemType,
        @NotNull UUID blockedItemId) {}
