package com.choruskube.core.repository;

import java.util.UUID;

/**
 * Projection of a soft-deleted {@link com.choruskube.core.model.WorkflowRun} — id for DB delete,
 * externalRunId and temporalNamespace for the Temporal {@code terminate} call. Used by
 * {@link com.choruskube.core.reconciler.WorkflowRunReconciler}'s native driver query so the
 * entity-level {@code @SQLRestriction("deleted_at IS NULL")} doesn't filter out tombstoned rows.
 *
 * <p>{@code externalRunId} may be null if the run was tombstoned before a Temporal workflow was
 * started (race during run-creation failure paths). Cleanup tolerates null — the Temporal
 * terminate is skipped and the DB hard-delete proceeds. {@code temporalNamespace} may be null for
 * a run that predates the column, which {@link com.choruskube.core.config.WorkflowClientRegistry}
 * resolves to the deployment's configured namespace.
 */
public interface TombstonedWorkflowRunRef {
    UUID getId();

    String getExternalRunId();

    String getTemporalNamespace();
}
