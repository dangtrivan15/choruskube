package com.choruskube.core.service;

import java.util.UUID;

/**
 * Decides whether a node execution may be dispatched to its run's task queue.
 *
 * <p>Optional seam. With no implementation every node is allowed, which is the behaviour
 * of a deployment whose activities are served by a single always-present worker.
 *
 * <p>Consulted once per node, after the node execution row exists — a denial must have a
 * row to attach its reason to, or the run fails with nothing for an operator to look at.
 */
public interface NodePlacementChecker {

    PlacementDecision check(UUID runId, UUID nodeExecutionId);

    /** @param reason operator-facing explanation; empty when allowed */
    record PlacementDecision(boolean allowed, String reason) {}
}
