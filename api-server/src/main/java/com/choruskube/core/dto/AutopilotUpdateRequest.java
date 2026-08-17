package com.choruskube.core.dto;

import jakarta.validation.constraints.Min;

/** PATCH body for the Autopilot singleton. A null {@code maxParallel} leaves it unchanged. */
public record AutopilotUpdateRequest(@Min(1) Integer maxParallel) {}
