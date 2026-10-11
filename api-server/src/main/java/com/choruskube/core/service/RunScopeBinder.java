package com.choruskube.core.service;

import java.util.UUID;

/**
 * Runs work done on a run's behalf with no request behind it — a held gate approval re-applied by
 * {@code GateStampReconciler} — inside whatever scope the run's collaborators need. Core is
 * single-tenant and binds nothing; a downstream implementation binds the run's organization.
 *
 * <p>An implementation must resolve the scope from {@code runId} alone; restore, not clear, any
 * scope the thread already had, in a {@code finally}; run {@code work} exactly once on the calling
 * thread and let its exceptions propagate; throw rather than skip when the scope cannot be bound;
 * and hold no transaction open around {@code work}, whose gate decision is deliberately
 * non-transactional (see {@code NodeExecutionClaimService}).
 */
public interface RunScopeBinder {

    void runInScopeOf(UUID runId, Runnable work);
}
