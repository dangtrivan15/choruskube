package com.choruskube.core.dto;

import jakarta.validation.constraints.Min;

/**
 * PATCH body for the Autopilot singleton. A null field leaves that ceiling unchanged — the two
 * ceilings are independent, so either may be omitted without touching the other.
 */
public record AutopilotUpdateRequest(
        @Min(1) Integer maxParallel, @Min(0) Integer maxAwaitingHuman) {}
