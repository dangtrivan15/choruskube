package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EpicResponse(
        UUID id,
        String title,
        String description,
        String motivation,
        String status,
        Progress progress,
        SoftwareProjectRef softwareProject,
        List<RepoRef> repos,
        Instant createdAt,
        Instant updatedAt) {

    /** Rollup completion figure derived from descendant Tasks (Decision 2) — never stored. */
    public record Progress(long totalTasks, long doneTasks) {}
}
