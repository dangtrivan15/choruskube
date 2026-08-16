package com.choruskube.core.service;

import java.util.UUID;

/**
 * Runs one Autopilot's pass inside whatever scope that Autopilot's collaborators need.
 *
 * <p>The Autopilot acts on an organisation's behalf from a scheduler thread, where no request and
 * therefore no request-scoped state exists. Core is single-tenant and has nothing to bind, so its
 * implementation simply runs the work. Downstream, a pass reaches {@code RunService.startRun} —
 * which calls collaborators that read request-scoped tenant state — so the binding this interface
 * establishes is the difference between an organisation's Autopilot starting work and it throwing
 * on every attempt until the failure breaker disengages it.
 *
 * <p>Core declares and invokes the boundary; a downstream implementation decides what binding
 * means. It is declared here because {@code AutopilotService.tick()} is core code and only core can
 * put a scope around it.
 *
 * <h2>What an implementation must do</h2>
 *
 * <ul>
 *   <li><strong>Unbind in a {@code finally}.</strong> One scheduler thread runs every
 *       organisation's pass in turn, so state left bound is picked up by the next organisation's
 *       pass — one organisation's work attributed to another, which is the defect class this seam
 *       exists to prevent, reintroduced by the fix for it.
 *   <li><strong>Resolve the organisation from {@code autopilotId}.</strong> Not from ambient state:
 *       on this thread there is none, and whatever a thread-local happens to be holding belongs to
 *       somebody else.
 *   <li><strong>Do not swallow.</strong> An exception out of {@code pass} must propagate. The
 *       caller isolates passes from one another and re-throws the first failure, and both depend on
 *       seeing it; a binder that caught it would report every pass as successful and, worse, keep
 *       the failure breaker from ever noticing a broken installation.
 *   <li><strong>Restore, do not clear.</strong> If a scope is somehow already bound, put the
 *       previous value back rather than clearing to empty, so a caller that had one is not left
 *       without it.
 * </ul>
 *
 * <p><strong>An implementation must not open a transaction.</strong> The pass it is handed is four
 * short transactions plus a tick lease, deliberately separate; wrapping it in one — a
 * {@code @Transactional} on the implementation is enough, since both phase templates are {@code
 * PROPAGATION_REQUIRED} — merges all of them into the single long transaction that structure exists
 * to remove. {@code AutopilotService.tick()} asserts there is no ambient transaction before it
 * starts, but that assertion runs before the binder is reached and so cannot see one opened here.
 */
public interface AutopilotScopeBinder {

    /**
     * Runs {@code pass} in the scope that owns {@code autopilotId}.
     *
     * @param autopilotId the Autopilot whose pass this is — the only thing naming a scope on a
     *     thread that has none
     * @param pass one complete pass over that Autopilot: claiming the tick lease, the four phases,
     *     and giving the lease back
     */
    void runInScopeOf(UUID autopilotId, Runnable pass);
}
