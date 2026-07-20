package com.choruskube.core.dto;

import java.util.UUID;

/**
 * {@code itemType} is one of {@code "epic_changed"}/{@code "story_changed"}/{@code
 * "task_changed"}/{@code "run_status_changed"}/{@code "dependency_changed"} (Decision 7 plus the
 * Roadmap Graph View dependency-edge addition). For {@code "dependency_changed"}, {@code itemId}
 * is the {@code work_item_dependency} row's own id, not either endpoint's id.
 */
public record RoadmapItemEvent(String itemType, UUID itemId, String status) {}
