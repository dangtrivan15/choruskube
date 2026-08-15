package com.choruskube.core.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param epicCount Count of Epics currently tagged with this Milestone, computed at read time from
 *     {@code EpicRepository} (single-Milestone reads via {@code countByMilestoneId}, list pages via
 *     the batched {@code findByMilestoneIdIn} grouping) — never persisted.
 */
public record MilestoneResponse(
        UUID id,
        String name,
        String description,
        UUID softwareProjectId,
        LocalDate targetDate,
        long epicCount,
        Instant createdAt,
        Instant updatedAt) {}
