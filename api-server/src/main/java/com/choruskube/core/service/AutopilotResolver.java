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
 * while {@link #findAllEngaged()} runs on a timer thread and must not. Selecting the row was the
 * last thing about the Autopilot still written as "there is exactly one" — the tick lease is keyed
 * on {@code autopilot.id} and {@link AutopilotCandidateSource} already takes it, so everything
 * downstream of this interface is per-Autopilot already.
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
     * <p>Which is exactly why publishing is not an obligation on implementations. An obligation is
     * something an implementer can forget, and forgetting this one is silent. Reporting {@code
     * created} truthfully is the only thing that can be got wrong here, and getting it wrong is
     * loud.
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
