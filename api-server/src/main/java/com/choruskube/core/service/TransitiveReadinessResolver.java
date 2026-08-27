package com.choruskube.core.service;

import com.choruskube.core.dto.BlockingChainNode;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
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
 * Shared transitive blocking-chain computation (multi-step blocking chain feature). A stateless utility — mirrors {@link RollupCalculator}'s package-private,
 * static-methods shape rather than being a Spring bean, since it has no repository/collaborator
 * dependencies of its own: every entry point takes an already-loaded, caller-bounded edge set plus
 * a status lookup, so the caller decides how far the graph is read (e.g. one Epic's
 * rows) and this class only walks whatever it's handed.
 *
 * <p>Used by {@link DefaultRoadmapGraphService} (per-node {@link Readiness} across an Epic), by
 * {@link InternalRunService} (root-cause "open blockers" for a run's Task), by {@link
 * DefaultWorkItemDependencyService} (rejecting cycle-forming edges at creation time), and by
 * {@link DefaultBlockingChainService} (the full pruned blocking-chain tree for one Story/Task) —
 * the single shared component all these call sites now delegate to, so they cannot independently
 * drift on what "blocked" means.
 */
final class TransitiveReadinessResolver {

    private TransitiveReadinessResolver() {}

    /**
     * Per-item readiness: an item is {@link Readiness#BLOCKED} if ANY item
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
     * The actionable root-cause blocker(s) of {@code itemId}: not-{@code done}
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
     * True if inserting "{@code newBlockingId} blocks {@code newBlockedId}" would make some item
     * permanently unfinishable, given {@code existingEdges} and the containment map {@code
     * parentOf} (child id → parent id; task→story, story→epic).
     *
     * <p>Walks the graph G where an edge {@code U → V} means "V cannot be done until U is done".
     * G has three kinds of edge, and all three are required — a walk over declared edges alone
     * misses deadlocks that containment creates:
     *
     * <ul>
     *   <li>declared: {@code U blocks V} contributes {@code U → V};
     *   <li>completion: a container is done only once its children are, contributing
     *       {@code child → parent};
     *   <li>inheritance: if {@code U} blocks a container {@code P}, then {@code U} blocks
     *       everything inside {@code P}, contributing {@code U → descendant(P)}.
     * </ul>
     *
     * <p>Example this exists to catch: {@code E1 blocks E2}, and a Story in {@code E2} blocks a
     * Story in {@code E1}. No declared cycle exists, yet neither Epic can ever complete.
     */
    static boolean wouldCreateCycle(
            UUID newBlockingId, UUID newBlockedId, List<WorkItemDependency> existingEdges, Map<UUID, UUID> parentOf) {
        if (newBlockingId.equals(newBlockedId)) {
            return true;
        }
        Map<UUID, List<UUID>> blocksOf = buildPrecedenceGraph(existingEdges, parentOf);
        addBlockingEdge(blocksOf, newBlockingId, newBlockedId, parentOf);

        // The new edge set added above is newBlockingId -> {newBlockedId} U descendants(newBlockedId)
        // (addBlockingEdge's own inheritance expansion) — a cycle exists if ANY of those heads can
        // reach back to newBlockingId, not just newBlockedId itself: the other heads are just as much
        // a starting point for a loop, and seeding the walk with newBlockedId alone misses cycles
        // that close through one of the inherited heads instead (e.g. the new edge is authored at
        // the container tier while the closing edge already exists at a descendant tier).
        Set<UUID> visited = new LinkedHashSet<>();
        Deque<UUID> stack = new ArrayDeque<>();
        List<UUID> seeds = new ArrayList<>();
        seeds.add(newBlockedId);
        parentOf.keySet().stream()
                .filter(id -> isDescendantOf(id, newBlockedId, parentOf))
                .forEach(seeds::add);
        for (UUID seed : seeds) {
            if (seed.equals(newBlockingId)) {
                return true;
            }
            if (visited.add(seed)) {
                stack.push(seed);
            }
        }
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

    /** Builds graph G (declared, completion, and inheritance edges — see {@link #wouldCreateCycle}). */
    private static Map<UUID, List<UUID>> buildPrecedenceGraph(
            List<WorkItemDependency> edges, Map<UUID, UUID> parentOf) {
        Map<UUID, List<UUID>> blocksOf = new HashMap<>();
        for (WorkItemDependency edge : edges) {
            addBlockingEdge(blocksOf, edge.getBlockingItemId(), edge.getBlockedItemId(), parentOf);
        }
        // Completion: a parent cannot be done until each child is.
        for (Map.Entry<UUID, UUID> entry : parentOf.entrySet()) {
            blocksOf.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }
        return blocksOf;
    }

    /** Adds {@code blockingId → blockedId} plus {@code blockingId → each descendant of blockedId}. */
    private static void addBlockingEdge(
            Map<UUID, List<UUID>> blocksOf, UUID blockingId, UUID blockedId, Map<UUID, UUID> parentOf) {
        blocksOf.computeIfAbsent(blockingId, k -> new ArrayList<>()).add(blockedId);
        for (Map.Entry<UUID, UUID> entry : parentOf.entrySet()) {
            if (isDescendantOf(entry.getKey(), blockedId, parentOf)) {
                blocksOf.computeIfAbsent(blockingId, k -> new ArrayList<>()).add(entry.getKey());
            }
        }
    }

    /**
     * True if {@code candidate} sits strictly under {@code ancestor} via one or more parent hops
     * ({@code candidate} itself does not count, even though the walk starts at its own parent).
     */
    private static boolean isDescendantOf(UUID candidate, UUID ancestor, Map<UUID, UUID> parentOf) {
        Set<UUID> seen = new LinkedHashSet<>();
        UUID current = parentOf.get(candidate);
        while (current != null && seen.add(current)) {
            if (current.equals(ancestor)) {
                return true;
            }
            current = parentOf.get(current);
        }
        return false;
    }

    /**
     * The pruned blocking-chain tree rooted at {@code itemId}: each returned
     * {@link BlockingChainNode} is a direct blocker of its parent (the root, for the top-level
     * list), carrying its own upstream blockers recursively. A branch is pruned entirely once every
     * node on it is {@code "done"} — but a {@code done} node is still INCLUDED if something further
     * upstream on the same branch is not done (mirrors {@link #isBlocked}'s core correctness rule:
     * "A blocks B blocks C; B done, A not done ⇒ C still BLOCKED" — the display must show B so the
     * user understands why the done-looking direct blocker still counts).
     *
     * <p>Bounded by {@code maxNodes} (total nodes admitted into the returned tree, across all
     * branches) and {@code maxDepth} (hops from the root); once either is hit, that branch stops
     * expanding and the walk is reported {@code truncated} via {@link ChainWalkResult#truncated()}
     * — the caller ({@code DefaultBlockingChainService}) applies its own, separate
     * node/depth cap while resolving items from the database layer-by-layer, so by the time {@code
     * edges} reaches here it is normally already within bounds; this method's own cap is a second,
     * independent guard against the tree itself fanning out past the cap through duplicate branches
     * to a shared ancestor (a shared ancestor is not deduplicated, so it can appear, and
     * count against the cap, more than once).
     *
     * <p>Cyclic data (pre-existing bad rows — {@code work_item_dependency} has no DB-level FK, see
     * its schema comment) is handled like {@link #isBlocked}: a blocker already on the current
     * recursion path is included as a leaf (not expanded again) rather than recursed into, so cyclic
     * data cannot hang this walk.
     *
     * @param itemTypeOf resolves an item id to its {@code BlockableItemType} (for {@code itemType}
     *     on each node)
     * @param titleOf resolves an item id to its display title
     */
    record ChainWalkResult(List<BlockingChainNode> blockedBy, boolean truncated) {}

    static ChainWalkResult blockingChainOf(
            UUID itemId,
            Function<UUID, BlockableItemType> itemTypeOf,
            List<WorkItemDependency> edges,
            Function<UUID, String> statusOf,
            Function<UUID, String> titleOf,
            int maxNodes,
            int maxDepth) {
        Map<UUID, List<UUID>> blockersOf = indexBlockersByBlockedItem(edges);
        int[] nodeCount = {0};
        boolean[] truncated = {false};
        List<BlockingChainNode> blockedBy = buildChainNodes(
                itemId,
                blockersOf,
                statusOf,
                titleOf,
                itemTypeOf,
                new LinkedHashSet<>(List.of(itemId)),
                1,
                maxDepth,
                nodeCount,
                maxNodes,
                truncated);
        return new ChainWalkResult(blockedBy, truncated[0]);
    }

    private static List<BlockingChainNode> buildChainNodes(
            UUID id,
            Map<UUID, List<UUID>> blockersOf,
            Function<UUID, String> statusOf,
            Function<UUID, String> titleOf,
            Function<UUID, BlockableItemType> itemTypeOf,
            Set<UUID> path,
            int depth,
            int maxDepth,
            int[] nodeCount,
            int maxNodes,
            boolean[] truncated) {
        List<BlockingChainNode> result = new ArrayList<>();
        for (UUID blockerId : blockersOf.getOrDefault(id, List.of())) {
            if (!path.add(blockerId)) {
                // Cyclic: include as a leaf without recursing again (fail-safe, mirrors isBlocked).
                String status = statusOf.apply(blockerId);
                if (!"done".equals(status)) {
                    result.add(new BlockingChainNode(
                            itemTypeOf.apply(blockerId).name(),
                            blockerId,
                            titleOf.apply(blockerId),
                            status,
                            List.of()));
                }
                continue;
            }
            try {
                if (depth > maxDepth || nodeCount[0] >= maxNodes) {
                    truncated[0] = true;
                    continue;
                }
                nodeCount[0]++;
                List<BlockingChainNode> childBlockedBy = buildChainNodes(
                        blockerId,
                        blockersOf,
                        statusOf,
                        titleOf,
                        itemTypeOf,
                        path,
                        depth + 1,
                        maxDepth,
                        nodeCount,
                        maxNodes,
                        truncated);
                String status = statusOf.apply(blockerId);
                boolean selfDone = "done".equals(status);
                if (!selfDone || !childBlockedBy.isEmpty()) {
                    result.add(new BlockingChainNode(
                            itemTypeOf.apply(blockerId).name(),
                            blockerId,
                            titleOf.apply(blockerId),
                            status,
                            childBlockedBy));
                }
            } finally {
                path.remove(blockerId);
            }
        }
        return result;
    }

    /**
     * True if any item reachable backward from {@code id} — through {@code done} AND not-{@code
     * done} blockers alike, per {@link #computeReadiness}'s contract — is not {@code done}. {@code
     * path} tracks the current recursion path (not a global visited set): a blocker already on
     * that path is cyclic data and is treated as permanently BLOCKED without recursing into it
     * again (the traversal guard, the second line of defense behind creation-time
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
}
