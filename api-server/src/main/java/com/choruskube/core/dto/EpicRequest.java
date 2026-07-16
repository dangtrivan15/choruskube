package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record EpicRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String description,
        String motivation,
        @NotNull UUID softwareProjectId) {}
