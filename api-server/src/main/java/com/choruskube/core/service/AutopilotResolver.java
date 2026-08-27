package com.choruskube.core.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which Autopilot a caller means. Core is single-tenant and always means the only one; downstream,
 * each organisation owns its own.
 *
 * <p>The two halves exist because they run in different worlds: {@link #forCurrentScope()} and
 * {@link #getOrCreateForCurrentScope()} serve a request and may read request-scoped tenant state,
 * while {@link #findAllEngaged()} runs on a timer thread and must not.
 */
public interface AutopilotResolver {

    /**
     * The caller's Autopilot, or empty when none has been configured. Request-scoped.
     *
     * <p>Never inserts: an absent row means this scope never configured an Autopilot, and a read
     * must not change that.
     */
    Optional<UUID> forCurrentScope();

    /**
     * The caller's Autopilot, creating it at its column defaults if absent. Request-scoped.
     *
     * <p><strong>Report the insert; do not act on it.</strong> An implementation says whether it
     * created the row and stops there — {@code AutopilotService} publishes the ownership event
     * ({@code MappableCreated.of("autopilot", id)}) off {@link Resolved#created()}, in the same
     * transaction as the insert. That event is what gives the row an owner downstream, and a row
     * without one is invisible to the scope provider that has to resolve it afterwards: the row
     * exists, the request returns 200, and the ownership row silently never does.
     *
     * <p>Creation lives on the request path only: the ownership event is resolved against
     * request-scoped tenant state, which a timer thread does not have.
     */
    Resolved getOrCreateForCurrentScope();

    /**
     * An Autopilot the caller may act on, and whether resolving it is what created the row.
     *
     * @param created true only on the call that inserted the row — never on a lookup that found
     *     one. It is the signal core publishes the ownership event off, so it is published once
     *     per row rather than on every mutation.
     */
    record Resolved(UUID id, boolean created) {}

    /**
     * The Autopilot that owns a resource, or empty when its scope has never configured one.
     *
     * <p>The timer-side counterpart to {@link #forCurrentScope()}, and the only resolution a
     * background caller may use. Scope is derived from the resource rather than from the thread, so
     * implementations must not read request-scoped tenant state and must not use {@code
     * ScopeProvider} — the same rule, and the same reason, as {@link #findAllEngaged()}.
     *
     * <p>Both failure modes of getting this wrong are worth naming, because a reconciler that
     * reached for {@code forCurrentScope()} instead would hit one of them and neither is visible in
     * core. It either throws for want of a tenant context, stranding whatever the caller was in the
     * middle of; or it resolves to whatever scope the timer thread happens to default to and stops
     * <em>that</em> organisation's Autopilot over a failure in a repository it does not own. The
     * second is the cross-organisation control defect this seam exists to remove, arrived at from
     * the back.
     *
     * @param resourceType an ownership type name, as {@code AuthorizationStrategy#assertSameOrg}
     *     takes — {@code git_repo}, {@code workflow_run}, {@code task}. Implementations normalize it
     *     the same way ({@code git_repo}/{@code repo_group} → {@code software_project}).
     * @param resourceId the resource whose owning scope is wanted
     */
    Optional<UUID> forResource(String resourceType, UUID resourceId);

    /**
     * Every engaged Autopilot in the installation, for the scheduler to pass over.
     *
     * <p>Called from a timer thread: implementations must not read request-scoped tenant state,
     * and must not use {@code ScopeProvider}. They must not create either — see {@link
     * #getOrCreateForCurrentScope()} for why creation cannot happen here.
     *
     * <p>Returning only the first, or only the oldest, is the defect this interface exists to
     * prevent: every other organisation's Autopilot would then never tick, and silently.
     *
     * <p><strong>Return at most one Autopilot per scope.</strong> Two concurrent first-writes can
     * leave a scope holding a second, orphan row, and an implementation that simply returns every
     * engaged row would hand the scheduler both: two passes over one organisation, each counting
     * the other's containers as free capacity, and {@code max_parallel} exceeded for as long as
     * the orphan exists. Selecting one row per scope — the same choice that makes the orphan inert
     * for {@link #forCurrentScope()} — is what keeps the budget a budget.
     */
    List<UUID> findAllEngaged();
}
