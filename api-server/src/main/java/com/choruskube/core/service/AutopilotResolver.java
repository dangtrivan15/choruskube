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
     * <p>Implementations that insert <strong>must</strong> publish {@code
     * MappableCreated.of("autopilot", id)} in the same transaction, immediately after the insert —
     * that event is what writes the row's ownership downstream, and a row without one is invisible
     * to the scope provider that has to resolve it afterwards. {@code "autopilot"} is a
     * cross-repository contract: it is the resource-type string the ownership writer switches on.
     *
     * <p>Creation lives on the request path only, and this is why: the ownership event is resolved
     * against request-scoped tenant state, which a timer thread does not have.
     */
    UUID getOrCreateForCurrentScope();

    /**
     * Every engaged Autopilot in the installation, for the scheduler to pass over.
     *
     * <p>Called from a timer thread: implementations must not read request-scoped tenant state,
     * and must not use {@code ScopeProvider}. They must not create either — see {@link
     * #getOrCreateForCurrentScope()} for why creation cannot happen here.
     *
     * <p>Returning only the first, or only the oldest, is the defect this interface exists to
     * prevent: every other organisation's Autopilot would then never tick, and silently.
     */
    List<UUID> findAllEngaged();
}
