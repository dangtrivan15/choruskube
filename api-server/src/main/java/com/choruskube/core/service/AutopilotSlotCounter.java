package com.choruskube.core.service;

import com.choruskube.core.model.enums.WorkflowRunStatus;
import java.util.Collection;
import java.util.UUID;

/**
 * How many live agent pods currently occupy a slot in an Autopilot's scope. The number {@code
 * max_parallel} is measured against, and the one thing that keeps the Autopilot from launching a
 * container the ceiling was meant to forbid.
 *
 * <p><strong>What "in scope" means is deployment-defined, which is the whole reason this is a
 * seam.</strong> Multi-tenant, a person starting a run by hand launches a real agent pod that costs
 * the same capacity as one the Autopilot launched, so the org's ceiling has to count it — otherwise
 * {@code max_parallel = 1} with one member's manual run in flight still reads as a free slot and the
 * Autopilot oversubscribes. The run's {@code autopilot_id} is the wrong key for that: it is null for
 * a person's run by design (see {@code RunResponse#autopilotId}). The overlay therefore scopes
 * occupancy by run ownership, counting every pod the organization owns. Single-tenant, there is no
 * per-run ownership to scope by and only one Autopilot, so the
 * default counts the Autopilot's own attributed runs — see {@link SingleTenantAutopilotSlotCounter}
 * for why installation-wide counting is neither needed nor testable there.
 *
 * <p>A seam rather than a direct query for the same reason as {@link AutopilotCandidateSource}: the
 * tick runs on a timer thread with no request scope, so the scope must be derived from the {@code
 * autopilotId} the caller already holds. Implementations must not read request-scoped tenant state.
 *
 * <p>The failure breaker deliberately does <strong>not</strong> go through here — it counts run
 * <em>outcomes</em> and only the Autopilot's own (a person cancelling a run is not the Autopilot
 * failing), so it keeps its own {@code autopilot_id}-keyed query. Occupancy and outcome are
 * different questions with different scopes.
 */
public interface AutopilotSlotCounter {

    /**
     * @param autopilotId the Autopilot asking; the only thing a background caller has to derive
     *     scope from. Implementations must not read request-scoped tenant state.
     * @param occupyingStatuses the run statuses that count as a live agent pod — supplied by the
     *     caller so "what occupies a slot" stays defined in exactly one place ({@code
     *     AutopilotService#classify}) rather than re-stated by each implementation.
     */
    long occupiedSlots(UUID autopilotId, Collection<WorkflowRunStatus> occupyingStatuses);
}
