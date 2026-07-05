package com.choruskube.core.model.enums;

import java.util.Set;

/**
 * Logical groupings of {@link WorkflowRunStatus} values used across multiple
 * services. Kept here (next to the enum) rather than on {@code QuotaService}
 * so callers can reach these constants without depending on the quota
 * machinery — particularly important for the OSS / single-tenant code path
 * which does not ship {@code QuotaService}.
 */
public final class WorkflowRunStatusGroups {

    private WorkflowRunStatusGroups() {}

    public static final Set<WorkflowRunStatus> ACTIVE = Set.of(
            WorkflowRunStatus.pending,
            WorkflowRunStatus.running,
            WorkflowRunStatus.paused,
            WorkflowRunStatus.awaiting_human,
            WorkflowRunStatus.awaiting_retry,
            WorkflowRunStatus.live_chat);
}
