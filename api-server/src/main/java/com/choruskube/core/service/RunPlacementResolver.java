package com.choruskube.core.service;

import java.util.Set;
import java.util.UUID;

/**
 * Chooses where a run executes.
 *
 * <p>Optional seam: a deployment with no implementation uses the configured
 * {@code temporal.namespace} and {@code temporal.task-queue} for every run, which is the
 * single-namespace behaviour this server has always had. That fallback lives in
 * {@link RunPlacementService}, never in a default method here — an implementation that exists
 * knows about more than one place work can run, so an unimplemented method answering "the
 * configured one" would route a run to a namespace nothing polls rather than failing to compile.
 *
 * <p>An implementation may reject a run outright by throwing; the call site sits beside the
 * credential pre-flight, so a rejection surfaces as a failed start rather than a run that stalls
 * with nothing listening.
 */
public interface RunPlacementResolver {

    /**
     * Decides where this run executes and records the choice so it cannot move later. Runs
     * inside the caller's transaction.
     *
     * @throws com.choruskube.core.exception.BadRequestException if the run cannot be placed
     */
    RunPlacement placeFor(UUID runId);

    /** Every namespace this deployment may place a run in. */
    Set<String> namespaces();
}
