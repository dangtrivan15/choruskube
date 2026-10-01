package com.choruskube.core.dto;

/**
 * Response for {@code POST .../roadmap-proposal/validate} on a valid document: a summary of what
 * the document would materialize, echoing the gate's mode and label so the CLI can tell the agent
 * which gate its proposal will be reviewed at.
 */
public record RoadmapProposalValidationResponse(
        String mode,
        String gateLabel,
        int newEpics,
        int newStories,
        int newTasks,
        int existingItems,
        int dependencies) {}
