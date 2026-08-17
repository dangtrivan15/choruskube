package com.choruskube.core.service;

import java.util.UUID;

/**
 * Stops the Autopilot because something outside it can no longer be observed. Implemented by {@link
 * AutopilotService}; injected wherever a component discovers that the world the Autopilot reasons
 * about has gone dark.
 *
 * <p>A one-method interface rather than the whole service on purpose. The Autopilot's dependency
 * graph is deliberately one-directional — nothing it starts work through knows it exists — and a
 * reconciler holding an {@code AutopilotService} could call {@code tick()}, {@code engage()} or
 * {@code update()} from a timer thread that has no business doing any of them. This names the one
 * thing such a caller is allowed to do, and the name says why it may.
 *
 * <p>Distinct from the failure breaker in {@code AutopilotService}, which counts <em>run</em>
 * outcomes and disengages on the third. Feeding an external failure into that counter would let one
 * credential hiccup plus two unrelated run failures disengage with a reason naming the wrong cause,
 * so this path disengages immediately and carries its own reason.
 */
public interface AutopilotSafetyValve {

    /**
     * Disengages the Autopilot that owns {@code resourceType}/{@code resourceId}, recording {@code
     * reason} for the human who has to fix whatever broke. A no-op — no write, no event — when that
     * scope has no Autopilot row or it is already disengaged, so a caller may report the same
     * failure on every pass without consequence.
     *
     * <p><strong>The resource is what names the scope, and it is not optional.</strong> Every caller
     * is on a timer thread, which has no tenant context to resolve against; the failing row is the
     * only thing that knows whose Autopilot this is. Without it the stop lands on whichever
     * Autopilot the thread defaulted to — one organisation stopped by a failure in a repository
     * belonging to another, which is worse than not stopping at all. See {@link
     * AutopilotResolver#forResource}.
     *
     * <p>It follows that a caller processing a batch must call this <em>per owning scope</em> rather
     * than once for the batch: downstream a batch spans organisations, and one stop is not the right
     * answer for all of them.
     *
     * <p>{@code reason} is rendered verbatim in the UI. It must name the resource and the fault in
     * terms a human can act on, and must never contain a token or a response body.
     *
     * @param resourceType an ownership type name — {@code git_repo}, {@code workflow_run}, {@code
     *     task} — as {@link AutopilotResolver#forResource} takes it
     * @param resourceId the resource whose Autopilot is to be stopped
     */
    void disengageForExternalFailure(String resourceType, UUID resourceId, String reason);
}
