package com.choruskube.core.dto;

import com.choruskube.core.model.enums.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Internal (agent-facing) request body for creating an Epic. The Epic's target is resolved
 * from the run's {@code software_project_id} input; agents do not pick a target directly — the
 * run's project IS the Epic's project.
 *
 * <p>{@code priority} is optional (nullable): an absent value defaults to {@code Priority.medium}
 * in the service layer, same as any hand-created Epic.
 *
 * <p>{@code milestoneId} is optional (nullable, Decision 4/6): an absent value leaves the Epic
 * unassigned to any Milestone, same as any hand-created Epic. When present it must resolve to a
 * Milestone in the run's own software project — enforced by {@code
 * InternalRunService.createEpic}/{@code EpicService.updateInternal}, not here.
 */
public record InternalCreateEpicRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String description,
        String motivation,
        Priority priority,
        UUID milestoneId) {

    /** Convenience overload for callers that don't set a priority/milestone. */
    public InternalCreateEpicRequest(String title, String description, String motivation) {
        this(title, description, motivation, null, null);
    }

    /** Convenience overload for callers that set a priority but no milestone. */
    public InternalCreateEpicRequest(String title, String description, String motivation, Priority priority) {
        this(title, description, motivation, priority, null);
    }
}
