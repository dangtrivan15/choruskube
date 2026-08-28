package com.choruskube.core.model.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * The complement of {@link #ACTIVE}: a run here has stopped moving and only a human or an
     * agent can advance whatever it was working on. Derived rather than listed, so a tenth
     * {@link WorkflowRunStatus} joins exactly one of the two groups instead of silently neither.
     */
    public static final Set<WorkflowRunStatus> TERMINAL = Arrays.stream(WorkflowRunStatus.values())
            .filter(status -> !ACTIVE.contains(status))
            .collect(Collectors.toUnmodifiableSet());
}
