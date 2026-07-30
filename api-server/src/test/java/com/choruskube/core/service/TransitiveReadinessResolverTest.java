package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
        assertThat(TransitiveReadinessResolver.wouldCreateCycle(A, B, List.of()))
                .isFalse();
    }

    @Test
    void wouldCreateCycle_directCycle_isTrue() {
        // A already blocks B; proposing B blocks A would close a 2-node loop.
        List<WorkItemDependency> edges = List.of(edge(A, B));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(B, A, edges)).isTrue();
    }

    @Test
    void wouldCreateCycle_indirectCycle_isTrue() {
        // A blocks B, B blocks C; proposing C blocks A would close a 3-node loop.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(C, A, edges)).isTrue();
    }

    @Test
    void wouldCreateCycle_nonCycleFormingEdgeAmongSameNodes_isFalse() {
        // A blocks B, B blocks C; proposing A blocks C is a valid extra edge, not a cycle.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(B, C));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(A, C, edges)).isFalse();
    }

    @Test
    void wouldCreateCycle_unrelatedBranch_isFalse() {
        // A blocks B, C blocks D — proposing D blocks A touches a disjoint branch, no cycle.
        List<WorkItemDependency> edges = List.of(edge(A, B), edge(C, D));

        assertThat(TransitiveReadinessResolver.wouldCreateCycle(D, A, edges)).isFalse();
    }
}
