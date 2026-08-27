package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A single candidate dependency edge (Decision 2): {@code blocking} blocks {@code blocked}. Both
 * values are candidate-local {@code key}s (never UUIDs — candidate items have no database id until
 * materialization), resolved by {@code RoadmapCandidateMaterializer} via the {@code key ->
 * (BlockableItemType, UUID)} map it builds as it creates Epics/Stories/Tasks. An edge whose key(s)
 * don't resolve, or that would close a cycle, is dropped rather than aborting the whole batch
 * (Decision 3).
 */
public record CandidateDependency(
        @NotBlank String blocking, @NotBlank String blocked) {}
