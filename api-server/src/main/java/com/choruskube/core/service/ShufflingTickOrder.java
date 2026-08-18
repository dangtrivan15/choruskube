package com.choruskube.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Default {@link AutopilotTickOrder}: random, re-rolled every tick.
 *
 * <p>Random rather than round-robin because the two differ only in the tail and round-robin costs a
 * durable cursor. A cursor is shared state that itself needs arbitration between replicas — the
 * problem the tick lease exists to solve, reintroduced one level up to decide who goes first. A
 * shuffle needs no coordination at all and gives every Autopilot the same expected position, which
 * is the property that was actually missing.
 *
 * <p>It also fixes the multi-replica case as a side effect. The lease is keyed per Autopilot row
 * and whoever loses it skips rather than waits, so passes are already safe to interleave — but with
 * every replica walking the same fixed list, the second one loses the lease on the first row, then
 * the second, then the third, marching through contended rows in lockstep before finding work.
 * Starting somewhere else makes that contention incidental instead of structural.
 *
 * <p>Single-tenant core has one Autopilot, where shuffling a one-element list is the identity — so
 * this changes nothing here and exists for the implementations that have more.
 *
 * <p><strong>Not a Spring bean, deliberately.</strong> {@link AutopilotService} holds it as the
 * fallback behind an {@code ObjectProvider}, so a downstream implementation replaces it simply by
 * existing. The obvious alternatives are both worse here: {@code @ConditionalOnMissingBean} is only
 * reliable inside auto-configuration — in an ordinary {@code @Configuration} it is evaluated when
 * that class is parsed, so whether it sees a downstream bean depends on scan order — and the
 * {@code @ConditionalOnProperty(auth.enabled)} idiom the sibling seams use is wrong for this one:
 * those seams have no safe default and *should* refuse to start without a replacement, whereas
 * ordering has one and must not make an implementation supply what it does not care about.
 */
class ShufflingTickOrder implements AutopilotTickOrder {

    // A plain Random, not SecureRandom: this decides whose pass runs first among Autopilots that
    // are all entitled to one, so predicting it gains a caller nothing worth the cost.
    private final Random random = new Random();

    @Override
    public List<UUID> order(List<UUID> engaged) {
        if (engaged.size() < 2) {
            // Also the core case, every tick: no copy, no allocation, and an immutable input is
            // handed straight back untouched.
            return engaged;
        }
        List<UUID> shuffled = new ArrayList<>(engaged);
        Collections.shuffle(shuffled, random);
        return shuffled;
    }
}
