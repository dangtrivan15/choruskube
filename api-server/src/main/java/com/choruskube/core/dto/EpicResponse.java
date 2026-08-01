package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code readyToStart} is {@code true} iff this Epic has at least one Story/Task that is both
 * still {@code backlog} (not started) and has no open blocker, per the same transitive
 * blocking-chain walk ({@code EpicReadinessAssembler}/{@code TransitiveReadinessResolver}) that
 * powers the per-Epic Graph View and the Story/Task list readiness badges — never a second,
 * competing definition of "blocked" (readiness rollup, ready-to-start filter). Always populated,
 * on every response this record backs (list, get, create, update), not only when the {@code
 * readyToStart} query filter is active.
 */
public record EpicResponse(
        UUID id,
        String title,
        String description,
        String motivation,
        String status,
        String stage,
        Progress progress,
        SoftwareProjectRef softwareProject,
        List<RepoRef> repos,
        Instant createdAt,
        Instant updatedAt,
        boolean readyToStart) {

    /** Rollup completion figure derived from descendant Tasks (Decision 2) — never stored. */
    public record Progress(long totalTasks, long doneTasks) {}
}
