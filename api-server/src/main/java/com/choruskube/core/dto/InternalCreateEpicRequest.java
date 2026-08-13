package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Internal (agent-facing) request body for creating an Epic. The Epic's target is resolved
 * from the run's {@code software_project_id} input; agents do not pick a target directly — the
 * run's project IS the Epic's project.
 *
 * <p>{@code priority} is optional (nullable): an absent value defaults to {@code Priority.medium}
 * in the service layer, same as any hand-created Epic.
 */
public record InternalCreateEpicRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description, String motivation, Priority priority) {

    /** Convenience overload for callers that don't set a priority (defaults to medium downstream). */
    public InternalCreateEpicRequest(String title, String description, String motivation) {
        this(title, description, motivation, null);
    }
}
