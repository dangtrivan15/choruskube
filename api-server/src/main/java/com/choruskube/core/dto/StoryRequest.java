package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create-only (POST) request body for a Story. The full PUT edit uses {@link StoryUpdateRequest}
 * instead, which carries no {@code priority} — priority is set once at create time and thereafter
 * moved via {@code PATCH /{id}/priority} only, mirroring the {@code stage} pattern.
 *
 * <p>{@code priority} is nullable here: an absent value defaults to {@code Priority.medium} in the
 * service layer.
 */
public record StoryRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description, Priority priority) {

    /** Convenience overload for callers that don't set a priority (defaults to medium downstream). */
    public StoryRequest(String title, String description) {
        this(title, description, null);
    }
}
