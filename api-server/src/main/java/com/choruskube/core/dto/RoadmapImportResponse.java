package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of a valid roadmap import: what the document describes and, unless it was a dry run, the
 * Epics it created. An invalid document never produces one — it is a 400 {@link
 * ValidationResponse} listing every error, and nothing is written.
 */
public record RoadmapImportResponse(
        boolean dryRun,
        int milestones,
        int newEpics,
        int newStories,
        int newTasks,
        int existingItems,
        int dependencies,
        List<UUID> createdEpicIds) {}
