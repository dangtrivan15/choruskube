package com.choruskube.core.dto;

import com.choruskube.core.model.enums.WorkItemStatus;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * {@code runId} is the workflow run this outcome is being reported for — optional; when present
 * it must match the Task's most recent linked run, guarding against a stale/racing caller
 * reporting an outcome for a run that is no longer the Task's current one. {@code note} is an
 * optional free-text outcome note, recorded on the audit trail but not persisted as a column.
 */
public record TaskStatusUpdateRequest(
        @NotNull WorkItemStatus status,
        @Nullable UUID runId,
        @Nullable String note) {}
