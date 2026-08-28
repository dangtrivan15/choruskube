package com.choruskube.core.dto;

import java.util.UUID;

/** {@code milestoneId == null} clears the Epic's Milestone assignment. */
public record EpicMilestoneUpdateRequest(UUID milestoneId) {}
