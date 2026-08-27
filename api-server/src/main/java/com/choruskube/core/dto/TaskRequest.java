package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code priority} is optional (nullable): an absent value defaults to {@code Priority.medium} in
 * the service layer, same as Epic/Story priority (Decision 4 of the roadmap dependencies/
 * priorities/milestones feature).
 */
public record TaskRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description, Priority priority) {

    /** Convenience overload for callers that don't set a priority (defaults to medium downstream). */
    public TaskRequest(String title, String description) {
        this(title, description, null);
    }
}
