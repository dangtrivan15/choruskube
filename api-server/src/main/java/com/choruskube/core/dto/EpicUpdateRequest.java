package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Full PUT edit body for an Epic — the pre-existing editable fields only. Deliberately carries no
 * {@code priority}: like {@code stage}, priority is not editable through the full PUT and is moved
 * via {@code PATCH /{id}/priority} instead. Split out of the now create-only {@link EpicRequest}.
 */
public record EpicUpdateRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String description,
        String motivation,
        @NotNull UUID softwareProjectId) {}
