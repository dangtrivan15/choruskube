package com.choruskube.core.service;

import java.util.UUID;

/**
 * Chooses which Temporal task queue a run's agent-step activities are dispatched to.
 *
 * <p>Optional seam, in the same shape as {@link QuotaChecker}: a deployment with no
 * implementation uses the configured {@code temporal.task-queue} for every run, which is
 * the single-queue behaviour this server has always had. An implementation may vary the
 * queue per run, and may reject the run outright by throwing — the call site sits beside
 * the credential pre-flight, so a rejection surfaces as a failed start rather than as a
 * run that stalls with nothing listening.
 */
public interface RunPlacementResolver {

    /**
     * @return the task queue for this run, or a blank string to use the configured default
     * @throws com.choruskube.core.exception.BadRequestException if the run cannot be placed
     */
    String taskQueueFor(UUID runId);
}
