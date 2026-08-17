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
 *   <li><strong>Restore, do not clear.</strong> A scope is often already bound: besides the
 *       scheduler, {@code AutopilotController} calls {@code tick()} from {@code POST
 *       /api/v1/autopilot/tick}, which is a request thread with its own scope, and the e2e suite
 *       drives exactly that endpoint. So put the previous value back rather than clearing to
 *       empty. This is a routine path, not a defensive one, and it is the case a scheduler-only
 *       test will never reach.
 *   <li><strong>Run the pass, exactly once, on the calling thread — and throw rather than
 *       skip.</strong> An implementation that resolves the scope optionally and runs the pass only
 *       when it succeeds does nothing at all when it does not: no exception, no failure recorded,
 *       nothing logged, and an Autopilot that is engaged and permanently idle. If the scope cannot
 *       be bound, that is a failure and must be thrown.
 * </ul>
 *
 * <p><strong>An implementation must hold no transaction open around the pass.</strong> Reading its
 * own state first is fine and downstream implementations need to — resolving which scope owns an
 * Autopilot is a database read. What must not happen is that read, or anything else, still being
 * inside an open transaction when the pass runs. The pass it is handed is four
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
     * @param autopilotId the Autopilot whose pass this is — the only thing naming a scope when the
     *     caller is the scheduler, and the authority even when the caller is a request that
     *     already has one of its own
     * @param pass one complete pass over that Autopilot: claiming the tick lease, the four phases,
     *     and giving the lease back
     * @throws RuntimeException if the scope cannot be established. A throw here skips {@code
     *     settle}, so it never reaches the failure breaker — an installation whose scopes cannot be
     *     resolved retries every interval rather than disengaging after three. That is deliberate:
     *     a platform fault should not spend an organisation's failure budget.
     */
    void runInScopeOf(UUID autopilotId, Runnable pass);
}
