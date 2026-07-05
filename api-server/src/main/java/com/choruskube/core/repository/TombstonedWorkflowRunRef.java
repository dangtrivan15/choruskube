package com.choruskube.core.repository;

import java.util.UUID;

/**
 * Projection of a soft-deleted {@link com.choruskube.core.model.WorkflowRun} — id for DB delete,
 * externalRunId for the Temporal {@code terminate} call. Used by
 * {@link com.choruskube.core.reconciler.WorkflowRunReconciler}'s native driver query so the
 * entity-level {@code @SQLRestriction("deleted_at IS NULL")} doesn't filter out tombstoned rows.
 *
 * <p>{@code externalRunId} may be null if the run was tombstoned before a Temporal workflow was
 * started (race during run-creation failure paths). Cleanup tolerates null — the Temporal
 * terminate is skipped and the DB hard-delete proceeds.
 */
public interface TombstonedWorkflowRunRef {
    UUID getId();

    String getExternalRunId();
}
