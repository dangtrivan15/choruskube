package com.choruskube.core.service;

import java.util.List;
import java.util.UUID;

/**
 * The order in which one tick visits the engaged Autopilots.
 *
 * <p>Separate from {@link AutopilotResolver#findAllEngaged()} because the two answer different
 * questions. Which Autopilots are engaged is a fact about the data; which of them goes first is a
 * scheduling policy. They used to share one answer, and the accident that produced was a fixed
 * order: a resolver has to sort by <em>something</em> for its own query to be well defined, and
 * whatever it sorted by silently became the tick order, forever, on every tick. The last
 * Autopilot in that order is last every single time, and every replica walks the identical list
 * and contends for the identical lease in lockstep.
 *
 * <p>Ordering here rather than in each resolver so that fairness is decided once, in code that can
 * be tested with more than one Autopilot in play — which core, being single-tenant, otherwise
 * cannot do.
 *
 * <h2>Contract</h2>
 *
 * <p><strong>Return a permutation.</strong> The result must contain exactly the ids that were
 * passed in — each of them, once, in some order. Never a subset, never a superset, never a
 * duplicate. {@link AutopilotService#tick()} checks this and throws rather than proceeding.
 *
 * <p>The check is not defensive tidiness; it is the same hazard {@link AutopilotScopeBinder}'s
 * fifth clause exists for. An implementation that quietly drops an id — a budget, a tier that ran
 * out of quota, a filter that was meant to be temporary — produces an Autopilot that is engaged,
 * ticking, and permanently idle: no start, no exception, no failure counted, nothing in the log to
 * notice it by. Admission control is a reasonable thing to want, but it has to be a decision the
 * system can see, so it does not get to arrive disguised as an ordering.
 *
 * <p><strong>Do not block, and do not open a transaction.</strong> This runs on the scheduler
 * thread before any pass begins, outside every phase boundary, and {@code tick()} asserts that no
 * transaction is active. Reading state to decide an order is fine; holding one while the passes run
 * is the defect the four-phase structure exists to remove.
 *
 * <p>The core default, {@link ShufflingTickOrder}, is registered {@code @ConditionalOnMissingBean}
 * rather than gated on {@code auth.enabled} like {@link AutopilotResolver} and {@link
 * AutopilotCandidateSource}. That difference is deliberate and load-bearing: those seams have no
 * safe default — falling back to "every Epic in the installation" would be a cross-scope leak — so
 * their core beans are absent downstream and the context refuses to start without a replacement.
 * Ordering does have a safe default. Shuffling is correct for everybody; an implementation replaces
 * it only to want something better.
 */
public interface AutopilotTickOrder {

    /**
     * @param engaged the engaged Autopilots, in whatever order the resolver produced. Never null;
     *     may be empty.
     * @return the same ids, reordered. Must be a permutation of {@code engaged}.
     */
    List<UUID> order(List<UUID> engaged);
}
