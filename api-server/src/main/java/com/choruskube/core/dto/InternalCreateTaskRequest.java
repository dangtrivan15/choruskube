package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Internal (agent-facing) request body for creating a Task under a Story.
 *
 * <p>{@code priority} is optional (nullable): an absent value defaults to {@code Priority.medium}
 * in the service layer, same as any hand-created Task.
 */
public record InternalCreateTaskRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description, Priority priority) {

    /** Convenience overload for callers that don't set a priority (defaults to medium downstream). */
    public InternalCreateTaskRequest(String title, String description) {
        this(title, description, null);
    }
}
