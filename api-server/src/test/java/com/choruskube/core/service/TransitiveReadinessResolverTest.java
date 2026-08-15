package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.choruskube.core.dto.BlockingChainNode;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.Readiness;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of {@link TransitiveReadinessResolver} in isolation — no Spring context, no
 * database — exercising the chain-walking algorithm directly against hand-built edge lists.
 */
class TransitiveReadinessResolverTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    private static WorkItemDependency edge(UUID blockingId, UUID blockedId) {
        WorkItemDependency dep = new WorkItemDependency();
        dep.setBlockingItemType(BlockableItemType.task);
        dep.setBlockingItemId(blockingId);
        dep.setBlockedItemType(BlockableItemType.task);
        dep.setBlockedItemId(blockedId);
        return dep;
    }

    private static Function<UUID, String> statusOf(Map<UUID, String> statuses) {
        return statuses::get;
    }

    private static Function<UUID, String> titleOf(Map<UUID, String> titles) {
        return id -> titles.getOrDefault(id, id.toString());
    }

    private static Function<UUID, BlockableItemType> itemTypeOf(Map<UUID, BlockableItemType> types) {
        return id -> types.getOrDefault(id, BlockableItemType.task);
    }

    // ── computeReadiness ───────────────────────────────────────────────────

    @Test
    void computeReadiness_noEdges_isReady() {
        Map<UUID, Readiness> result =
                TransitiveReadinessResolver.computeReadiness(Set.of(A), List.of(), statusOf(Map.of()));

        assertThat(result.get(A)).isEqualTo(Readiness.READY);
    }

    @Test
    void computeReadiness_singleDirectBlockerNotDone_isBlocked() {
        List<WorkItemDependency> edges = List.of(edge(A, B));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog");

        Map<UUID, Readiness> result =
                TransitiveReadinessResolver.computeReadiness(Set.of(A, B), edges, statusOf(statuses));

        assertThat(result.get(B)).isEqualTo(Readiness.BLOCKED);
        assertThat(result.get(A)).isEqualTo(Readiness.READY);
    }

    @Test
    void computeReadiness_singleDirectBlockerDone_isReady() {
        List<WorkItemDependency> edges = List.of(edge(A, B));
        Map<UUID, String> statuses = Map.of(A, "done", B, "backlog");

        Map<UUID, Readiness> result =
                TransitiveReadinessResolver.computeReadiness(Set.of(A, B), edges, statusOf(statuses));

        assertThat(result.get(B)).isEqualTo(Readiness.READY);
    }

    @Test
    void computeReadiness_threeNodeChain_onlyRootUndone_middleAndTailBothBlocked() {
        // A blocks B, B blocks C. Only A is undone.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog", C, "backlog");

        Map<UUID, Readiness> result =
                TransitiveReadinessResolver.computeReadiness(Set.of(A, B, C), edges, statusOf(statuses));

        assertThat(result.get(B)).isEqualTo(Readiness.BLOCKED);
        assertThat(result.get(C)).isEqualTo(Readiness.BLOCKED);
        assertThat(result.get(A)).isEqualTo(Readiness.READY);
    }

    @Test
    void computeReadiness_threeNodeChain_middleDoneRootUndone_tailStillBlocked() {
        // A blocks B, B blocks C. B is marked done, but A (further upstream) is not — the core
        // regression this feature fixes: C must not flip to READY just because its own direct
        // blocker cleared.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "done", C, "backlog");

        Map<UUID, Readiness> result =
                TransitiveReadinessResolver.computeReadiness(Set.of(A, B, C), edges, statusOf(statuses));

        assertThat(result.get(C)).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void computeReadiness_threeNodeChain_onlyMiddleUndone_onlyImmediatelyDependentNodeBlocked() {
        // A blocks B, B blocks C. Only B (the middle) is undone; A and C are done.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "done", B, "backlog", C, "done");

        Map<UUID, Readiness> result =
                TransitiveReadinessResolver.computeReadiness(Set.of(A, B, C), edges, statusOf(statuses));

        // C is directly blocked by not-done B.
        assertThat(result.get(C)).isEqualTo(Readiness.BLOCKED);
        // B's own blocker (A) is done, so B itself is not blocked by anything upstream.
        assertThat(result.get(B)).isEqualTo(Readiness.READY);
        assertThat(result.get(A)).isEqualTo(Readiness.READY);
    }

    @Test
    void computeReadiness_diamondDependency_oneUndoneBlockerIsEnoughToBlock() {
        // C is blocked by both A and B. Only A is undone.
        List<WorkItemDependency> edges = List.of(edge(A, C), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "done", C, "backlog");

        Map<UUID, Readiness> result =
                TransitiveReadinessResolver.computeReadiness(Set.of(A, B, C), edges, statusOf(statuses));

        assertThat(result.get(C)).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void computeReadiness_twoNodeCycle_resolvesBlockedWithoutHanging() {
        // A blocks B, B blocks A — cyclic data that creation-time prevention should normally
        // reject, fed directly into the resolver to prove the traversal guard (Decision 5) is a
        // real second line of defense.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, A));
        Map<UUID, String> statuses = Map.of(A, "done", B, "done");

        assertThatCode(() -> {
                    Map<UUID, Readiness> result =
                            TransitiveReadinessResolver.computeReadiness(Set.of(A, B), edges, statusOf(statuses));
                    assertThat(result.get(A)).isEqualTo(Readiness.BLOCKED);
                    assertThat(result.get(B)).isEqualTo(Readiness.BLOCKED);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void computeReadiness_threeNodeCycle_resolvesBlockedWithoutHanging() {
        // A blocks B, B blocks C, C blocks A.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C), edge(C, A));
        Map<UUID, String> statuses = Map.of(A, "done", B, "done", C, "done");

        assertThatCode(() -> {
                    Map<UUID, Readiness> result =
                            TransitiveReadinessResolver.computeReadiness(Set.of(A, B, C), edges, statusOf(statuses));
                    assertThat(result.get(A)).isEqualTo(Readiness.BLOCKED);
                    assertThat(result.get(B)).isEqualTo(Readiness.BLOCKED);
                    assertThat(result.get(C)).isEqualTo(Readiness.BLOCKED);
                })
                .doesNotThrowAnyException();
    }

    // ── rootCauseBlockersOf ────────────────────────────────────────────────

    @Test
    void rootCauseBlockersOf_threeNodeChain_returnsOnlyTheRootNotTheMiddle() {
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog", C, "backlog");

        List<UUID> rootCauses = TransitiveReadinessResolver.rootCauseBlockersOf(C, edges, statusOf(statuses));

        assertThat(rootCauses).containsExactly(A);
    }

    @Test
    void rootCauseBlockersOf_middleDoneRootUndone_returnsOnlyTheRoot() {
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "done", C, "backlog");

        List<UUID> rootCauses = TransitiveReadinessResolver.rootCauseBlockersOf(C, edges, statusOf(statuses));

        assertThat(rootCauses).containsExactly(A);
    }

    @Test
    void rootCauseBlockersOf_noBlockers_isEmpty() {
        List<UUID> rootCauses = TransitiveReadinessResolver.rootCauseBlockersOf(A, List.of(), statusOf(Map.of()));

        assertThat(rootCauses).isEmpty();
    }

    @Test
    void rootCauseBlockersOf_diamond_bothLeavesReportedWhenBothUndone() {
        List<WorkItemDependency> edges = List.of(edge(A, C), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog", C, "backlog");

        List<UUID> rootCauses = TransitiveReadinessResolver.rootCauseBlockersOf(C, edges, statusOf(statuses));

        assertThat(rootCauses).containsExactlyInAnyOrder(A, B);
    }

    // ── wouldCreateCycle ───────────────────────────────────────────────────

    @Test
    void wouldCreateCycle_noExistingEdges_isFalse() {
        assertThat(TransitiveReadinessResolver.wouldCreateCycle(A, B, List.of(), Map.of()))
                .isFalse();
    }

    @Test
    void wouldCreateCycle_directCycle_isTrue() {
        // A already blocks B; proposing B blocks A would close a 2-node loop.
        List<WorkItemDependency> edges = List.of(edge(A, B));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(B, A, edges, Map.of()))
                .isTrue();
    }

    @Test
    void wouldCreateCycle_indirectCycle_isTrue() {
        // A blocks B, B blocks C; proposing C blocks A would close a 3-node loop.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(C, A, edges, Map.of()))
                .isTrue();
    }

    @Test
    void wouldCreateCycle_nonCycleFormingEdgeAmongSameNodes_isFalse() {
        // A blocks B, B blocks C; proposing A blocks C is a valid extra edge, not a cycle.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(A, C, edges, Map.of()))
                .isFalse();
    }

    @Test
    void wouldCreateCycle_unrelatedBranch_isFalse() {
        // A blocks B, C blocks D — proposing D blocks A touches a disjoint branch, no cycle.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(C, D));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(D, A, edges, Map.of()))
                .isFalse();
    }

    /**
     * E1 blocks E2, and a Story inside E2 blocks a Story inside E1. No cycle exists among the
     * declared edges alone, but the two Epics can never both complete: E1 cannot finish until
     * StoryA finishes, StoryA is blocked by StoryB, and StoryB inherits E1's block through E2.
     */
    @Test
    void wouldCreateCycle_crossTierDeadlockThroughContainment_isDetected() {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID storyInE1 = UUID.randomUUID();
        UUID storyInE2 = UUID.randomUUID();

        Map<UUID, UUID> parentOf = Map.of(storyInE1, e1, storyInE2, e2);

        // Already declared: E1 blocks E2. This class's existing edge(blockingId, blockedId) helper
        // stamps BlockableItemType.task on both ends; the cycle walk is purely id-based, so the
        // tier recorded on the row makes no difference to what is being tested here.
        List<WorkItemDependency> existing = List.of(edge(e1, e2));

        // Proposed: storyInE2 blocks storyInE1 — closes the loop through containment.
        assertThat(TransitiveReadinessResolver.wouldCreateCycle(storyInE2, storyInE1, existing, parentOf))
                .isTrue();
    }

    @Test
    void wouldCreateCycle_epicBlocksEpicWithNoOtherEdges_isNotACycle() {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID storyInE1 = UUID.randomUUID();
        UUID storyInE2 = UUID.randomUUID();
        Map<UUID, UUID> parentOf = Map.of(storyInE1, e1, storyInE2, e2);

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(e1, e2, List.of(), parentOf))
                .isFalse();
    }

    @Test
    void wouldCreateCycle_crossTierDeadlockWithContainerEdgeAuthoredSecond_isDetected() {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID storyInE1 = UUID.randomUUID();
        UUID storyInE2 = UUID.randomUUID();
        Map<UUID, UUID> parentOf = Map.of(storyInE1, e1, storyInE2, e2);

        // Same deadlock as the test above, authored in the opposite order: the Story-level edge
        // already exists and the container-level edge is the one being proposed.
        List<WorkItemDependency> existing = List.of(edge(storyInE2, storyInE1));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(e1, e2, existing, parentOf))
                .isTrue();
    }

    @Test
    void wouldCreateCycle_sameDirectionStoryEdgeUnderBlockedEpics_isNotACycle() {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID storyInE1 = UUID.randomUUID();
        UUID storyInE2 = UUID.randomUUID();
        Map<UUID, UUID> parentOf = Map.of(storyInE1, e1, storyInE2, e2);

        // E1 already blocks E2; a Story edge pointing the SAME way adds no loop.
        List<WorkItemDependency> existing = List.of(edge(e1, e2));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(storyInE1, storyInE2, existing, parentOf))
                .isFalse();
    }

    @Test
    void wouldCreateCycle_selfBlockThroughOwnParent_isDetected() {
        UUID epic = UUID.randomUUID();
        UUID story = UUID.randomUUID();
        Map<UUID, UUID> parentOf = Map.of(story, epic);

        // "The Epic blocks its own Story" — the Story can never finish, so the Epic never can.
        assertThat(TransitiveReadinessResolver.wouldCreateCycle(epic, story, List.of(), parentOf))
                .isTrue();
    }

    // ── blockingChainOf ────────────────────────────────────────────────────

    @Test
    void blockingChainOf_singleDirectBlocker_returnsOneLeafNode() {
        List<WorkItemDependency> edges = List.of(edge(A, B));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog");

        TransitiveReadinessResolver.ChainWalkResult result = TransitiveReadinessResolver.blockingChainOf(
                B, itemTypeOf(Map.of()), edges, statusOf(statuses), titleOf(Map.of()), 200, 20);

        assertThat(result.blockedBy()).hasSize(1);
        assertThat(result.blockedBy().get(0).itemId()).isEqualTo(A);
        assertThat(result.blockedBy().get(0).blockedBy()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void blockingChainOf_linearChainOfThree_nestsMiddleUnderTail() {
        // A blocks B, B blocks C — none done.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog", C, "backlog");

        TransitiveReadinessResolver.ChainWalkResult result = TransitiveReadinessResolver.blockingChainOf(
                C, itemTypeOf(Map.of()), edges, statusOf(statuses), titleOf(Map.of()), 200, 20);

        assertThat(result.blockedBy()).hasSize(1);
        BlockingChainNode bNode = result.blockedBy().get(0);
        assertThat(bNode.itemId()).isEqualTo(B);
        assertThat(bNode.blockedBy()).hasSize(1);
        assertThat(bNode.blockedBy().get(0).itemId()).isEqualTo(A);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void blockingChainOf_twoIndependentDirectBlockers_bothAppearAtDepthOne() {
        // C is blocked by both A and B, neither done.
        List<WorkItemDependency> edges = List.of(edge(A, C), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog", C, "backlog");

        TransitiveReadinessResolver.ChainWalkResult result = TransitiveReadinessResolver.blockingChainOf(
                C, itemTypeOf(Map.of()), edges, statusOf(statuses), titleOf(Map.of()), 200, 20);

        assertThat(result.blockedBy()).extracting(BlockingChainNode::itemId).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void blockingChainOf_doneIntermediateWithUndoneAncestor_stillIncluded() {
        // A blocks B, B blocks C. B is done, but A (further upstream) is not — B must still be
        // shown so the user understands why C is still blocked.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "done", C, "backlog");

        TransitiveReadinessResolver.ChainWalkResult result = TransitiveReadinessResolver.blockingChainOf(
                C, itemTypeOf(Map.of()), edges, statusOf(statuses), titleOf(Map.of()), 200, 20);

        assertThat(result.blockedBy()).hasSize(1);
        BlockingChainNode bNode = result.blockedBy().get(0);
        assertThat(bNode.itemId()).isEqualTo(B);
        assertThat(bNode.status()).isEqualTo("done");
        assertThat(bNode.blockedBy()).hasSize(1);
        assertThat(bNode.blockedBy().get(0).itemId()).isEqualTo(A);
    }

    @Test
    void blockingChainOf_fullyDoneBranch_isPrunedEntirely() {
        // A blocks B, B blocks C. Both A and B are done — the whole branch is resolved, so it
        // must not appear in C's chain at all.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));
        Map<UUID, String> statuses = Map.of(A, "done", B, "done", C, "backlog");

        TransitiveReadinessResolver.ChainWalkResult result = TransitiveReadinessResolver.blockingChainOf(
                C, itemTypeOf(Map.of()), edges, statusOf(statuses), titleOf(Map.of()), 200, 20);

        assertThat(result.blockedBy()).isEmpty();
    }

    @Test
    void blockingChainOf_cyclicEdges_doesNotHangAndReturns() {
        // A blocks B, B blocks A.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, A));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog");

        assertThatCode(() -> TransitiveReadinessResolver.blockingChainOf(
                        B, itemTypeOf(Map.of()), edges, statusOf(statuses), titleOf(Map.of()), 200, 20))
                .doesNotThrowAnyException();
    }

    @Test
    void blockingChainOf_smallMaxNodes_setsTruncatedTrue() {
        // A blocks B, B blocks C, C blocks D — longer than a maxNodes of 1.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C), edge(C, D));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog", C, "backlog", D, "backlog");

        TransitiveReadinessResolver.ChainWalkResult result = TransitiveReadinessResolver.blockingChainOf(
                D, itemTypeOf(Map.of()), edges, statusOf(statuses), titleOf(Map.of()), 1, 20);

        assertThat(result.truncated()).isTrue();
    }

    @Test
    void blockingChainOf_smallMaxDepth_setsTruncatedTrue() {
        // A blocks B, B blocks C, C blocks D — longer than a maxDepth of 1.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C), edge(C, D));
        Map<UUID, String> statuses = Map.of(A, "backlog", B, "backlog", C, "backlog", D, "backlog");

        TransitiveReadinessResolver.ChainWalkResult result = TransitiveReadinessResolver.blockingChainOf(
                D, itemTypeOf(Map.of()), edges, statusOf(statuses), titleOf(Map.of()), 200, 1);

        assertThat(result.truncated()).isTrue();
    }
}
