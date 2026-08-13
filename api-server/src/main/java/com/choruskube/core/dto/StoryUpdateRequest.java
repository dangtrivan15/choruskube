package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Full PUT edit body for a Story — the pre-existing editable fields only. Deliberately carries no
 * {@code priority}: like {@code stage}, priority is not editable through the full PUT and is moved
 * via {@code PATCH /{id}/priority} instead. Split out of the now create-only {@link StoryRequest}.
 */
public record StoryUpdateRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description) {}
