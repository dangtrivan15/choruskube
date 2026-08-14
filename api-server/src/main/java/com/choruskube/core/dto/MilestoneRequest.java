package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** Create (POST) request body for a Milestone. */
public record MilestoneRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        @NotNull UUID softwareProjectId,
        LocalDate targetDate) {}
