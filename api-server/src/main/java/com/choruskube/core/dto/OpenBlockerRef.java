package com.choruskube.core.dto;

import java.util.UUID;

/**
 * A reference to one of a Task's own direct, not-yet-{@code done} incoming blocking edges
 * (Decision 1), surfaced to the agent alongside {@link GraphRuntimeSnapshotResponse.TaskContext}
 * so a run knows about unresolved prerequisites up front (Decision 3). Unlike {@link
 * ExternalBlockerRef}, this is not Epic-scoped — {@code itemType}/{@code itemId} identify the
 * blocking Story/Task regardless of which Epic it belongs to (Decision 4).
 */
public record OpenBlockerRef(String itemType, UUID itemId, String title, String status) {}
