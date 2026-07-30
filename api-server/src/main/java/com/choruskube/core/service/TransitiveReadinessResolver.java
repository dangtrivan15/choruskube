package com.choruskube.core.service;

import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.Readiness;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared transitive blocking-chain computation (multi-step blocking chain feature, Decisions
 * 1/2/3/5). A stateless utility — mirrors {@link RollupCalculator}'s package-private,
 * static-methods shape rather than being a Spring bean, since it has no repository/collaborator
 * dependencies of its own: every entry point takes an already-loaded, caller-bounded edge set plus
 * a status lookup, so the caller decides how far the graph is read (Decision 2 — e.g. one Epic's
 * rows) and this class only walks whatever it's handed.
 *
 * <p>Used by {@link DefaultRoadmapGraphService} (per-node {@link Readiness} across an Epic), by
 * {@link InternalRunService} (root-cause "open blockers" for a run's Task), and by {@link
 * DefaultWorkItemDependencyService} (rejecting cycle-forming edges at creation time) — the single
 * shared component both readiness call sites now delegate to, so they cannot independently drift
 * on what "blocked" means (Decision 3).
 */
final class TransitiveReadinessResolver {

    private TransitiveReadinessResolver() {}

    /**
     * Per-item readiness (Decision 1/2): an item is {@link Readiness#BLOCKED} if ANY item
     * reachable by walking backward along blocking edges from it — not just its direct blocker —
     * is not yet {@code "done"} per {@code statusOf}. The walk continues past an already-{@code
     * done} intermediate blocker to check further upstream (this is the core regression this
     * feature fixes: A blocks B blocks C — marking B done while A is still not done must leave C
     * BLOCKED). {@code edges} is the caller's already-bounded candidate set (e.g. one Epic's rows,
     * see {@link DefaultRoadmapGraphService#assemble}); this method does not fetch anything
     * further itself, so the walk is bounded to exactly whatever the caller loaded.
     *
     * @return a readiness value for every id in {@code candidateIds}
     */
    static Map<UUID, Readiness> computeReadiness(
            Set<UUID> candidateIds, List<WorkItemDependency> edges, Function<UUID, String> statusOf) {
        Map<UUID, List<UUID>> blockersOf = indexBlockersByBlockedItem(edges);
        Map<UUID, Readiness> result = new HashMap<>();
        for (UUID id : candidateIds) {
            boolean blocked = isBlocked(id, blockersOf, statusOf, new LinkedHashSet<>(List.of(id)));
            result.put(id, blocked ? Readiness.BLOCKED : Readiness.READY);
        }
        return result;
    }

    /**
     * The actionable root-cause blocker(s) of {@code itemId} (Decision 4): not-{@code done}
     * ancestors of {@code itemId} (walking the full chain per {@link #computeReadiness}, not just
     * direct blockers) that are themselves {@link Readiness#READY} — i.e. have no not-{@code done}
     * blocker of their own — so an intermediate link that is itself still blocked is never
     * reported; only the item(s) actually worth acting on next are.
     */
    static List<UUID> rootCauseBlockersOf(
            UUID itemId, List<WorkItemDependency> edges, Function<UUID, String> statusOf) {
        Map<UUID, List<UUID>> blockersOf = indexBlockersByBlockedItem(edges);
        Set<UUID> ancestors = collectAncestors(itemId, blockersOf);
        List<UUID> rootCauses = new ArrayList<>();
        for (UUID candidate : ancestors) {
            if ("done".equals(statusOf.apply(candidate))) {
                continue;
            }
            boolean itselfBlocked = isBlocked(candidate, blockersOf, statusOf, new LinkedHashSet<>(List.of(candidate)));
            if (!itselfBlocked) {
                rootCauses.add(candidate);
            }
        }
        return rootCauses;
    }

    /**
     * True if inserting a new "{@code newBlockingId} blocks {@code newBlockedId}" edge would close
     * a cycle with {@code existingEdges} (Decision 5) — i.e. {@code newBlockingId} is already
     * reachable FROM {@code newBlockedId} by walking forward along existing "blocks" edges, so the
     * new edge would complete a loop back to where it started.
     */
    static boolean wouldCreateCycle(UUID newBlockingId, UUID newBlockedId, List<WorkItemDependency> existingEdges) {
        if (newBlockingId.equals(newBlockedId)) {
            return true;
        }
        Map<UUID, List<UUID>> blocksOf = indexBlockedByBlockingItem(existingEdges);
        Set<UUID> visited = new LinkedHashSet<>();
        Deque<UUID> stack = new ArrayDeque<>();
        stack.push(newBlockedId);
        visited.add(newBlockedId);
        while (!stack.isEmpty()) {
            UUID current = stack.pop();
            for (UUID next : blocksOf.getOrDefault(current, List.of())) {
                if (next.equals(newBlockingId)) {
                    return true;
                }
                if (visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return false;
    }

    /**
     * True if any item reachable backward from {@code id} — through {@code done} AND not-{@code
     * done} blockers alike, per {@link #computeReadiness}'s contract — is not {@code done}. {@code
     * path} tracks the current recursion path (not a global visited set): a blocker already on
     * that path is cyclic data and is treated as permanently BLOCKED without recursing into it
     * again (Decision 5's traversal guard, the second line of defense behind creation-time
     * rejection), which is also what keeps this method from looping forever on pre-existing or
     * cross-boundary cyclic data.
     */
    private static boolean isBlocked(
            UUID id, Map<UUID, List<UUID>> blockersOf, Function<UUID, String> statusOf, Set<UUID> path) {
        boolean blocked = false;
        for (UUID blockerId : blockersOf.getOrDefault(id, List.of())) {
            if (!path.add(blockerId)) {
                blocked = true; // cyclic: fail safe to permanently BLOCKED, don't recurse further
                continue;
            }
            try {
                if (!"done".equals(statusOf.apply(blockerId))) {
                    blocked = true;
                }
                if (isBlocked(blockerId, blockersOf, statusOf, path)) {
                    blocked = true;
                }
            } finally {
                path.remove(blockerId);
            }
        }
        return blocked;
    }

    /**
     * Every item reachable backward from {@code id} along blocking edges, regardless of status.
     * Guarded with a plain (not per-path) visited set — completeness is all that's needed here,
     * not cycle-vs-not distinction, so once an id has been expanded once there's nothing more to
     * learn by expanding it again.
     */
    private static Set<UUID> collectAncestors(UUID id, Map<UUID, List<UUID>> blockersOf) {
        Set<UUID> visited = new LinkedHashSet<>();
        Deque<UUID> stack = new ArrayDeque<>();
        stack.push(id);
        while (!stack.isEmpty()) {
            UUID current = stack.pop();
            for (UUID blockerId : blockersOf.getOrDefault(current, List.of())) {
                if (visited.add(blockerId)) {
                    stack.push(blockerId);
                }
            }
        }
        return visited;
    }

    /** {@code blockedItemId -> [blockingItemId, ...]} — "who blocks this item". */
    private static Map<UUID, List<UUID>> indexBlockersByBlockedItem(List<WorkItemDependency> edges) {
        Map<UUID, List<UUID>> blockersOf = new HashMap<>();
        for (WorkItemDependency edge : edges) {
            blockersOf
                    .computeIfAbsent(edge.getBlockedItemId(), k -> new ArrayList<>())
                    .add(edge.getBlockingItemId());
        }
        return blockersOf;
    }

    /** {@code blockingItemId -> [blockedItemId, ...]} — "what this item blocks". */
    private static Map<UUID, List<UUID>> indexBlockedByBlockingItem(List<WorkItemDependency> edges) {
        Map<UUID, List<UUID>> blocksOf = new HashMap<>();
        for (WorkItemDependency edge : edges) {
            blocksOf.computeIfAbsent(edge.getBlockingItemId(), k -> new ArrayList<>())
                    .add(edge.getBlockedItemId());
        }
        return blocksOf;
    }
}
