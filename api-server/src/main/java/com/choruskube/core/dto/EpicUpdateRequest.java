package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Deliberately carries no {@code priority}: like {@code stage}, priority is not editable through
 * the full PUT and is moved via {@code PATCH /{id}/priority} instead.
 */
public record EpicUpdateRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String description,
        String motivation,
        @NotNull UUID softwareProjectId) {}
