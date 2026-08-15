package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.ExternalBlockerRef;
import com.choruskube.core.dto.RoadmapGraphSnapshot;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.BlockerDirection;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class RoadmapGraphServiceTest extends BaseTest {

    @Autowired
    private RoadmapGraphService graphService;

    @Autowired
    private WorkItemDependencyService dependencyService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private TaskRepository taskRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private AuthorizationService authService;

    @BeforeEach
    void setUp() {
        WorkflowStub mockStub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
    }

    @Test
    void getGraph_returnsAllStoriesAndTasksUnderEpic() {
        EpicResponse epic = makeEpic("https://github.com/test/graph-nodes.git");
        StoryResponse s1 = makeStory(epic.id(), "Story 1");
        StoryResponse s2 = makeStory(epic.id(), "Story 2");
        List<StoryResponse> stories = List.of(s1, s2);
        List<TaskResponse> tasks = stories.stream()
                .flatMap(s -> List.of(makeTask(s.id(), "T1"), makeTask(s.id(), "T2"), makeTask(s.id(), "T3")).stream())
                .toList();

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        assertThat(snapshot.epic().id()).isEqualTo(epic.id());
        assertThat(snapshot.stories()).extracting(StoryResponse::id).containsExactlyInAnyOrder(s1.id(), s2.id());
        assertThat(snapshot.tasks()).hasSize(6);
        assertThat(snapshot.tasks())
                .extracting(TaskResponse::id)
                .containsExactlyInAnyOrderElementsOf(
                        tasks.stream().map(TaskResponse::id).toList());
        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.externalBlockers()).isEmpty();
    }

    @Test
    void getGraph_epicAndStoriesCarryPriority() {
        EpicResponse epic = makeEpic("https://github.com/test/graph-priority.git");
        epicService.updatePriority(epic.id(), com.choruskube.core.model.enums.Priority.high);
        StoryResponse story = makeStory(epic.id(), "Priority Story");
        storyService.updatePriority(story.id(), com.choruskube.core.model.enums.Priority.low);

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        assertThat(snapshot.epic().priority()).isEqualTo("high");
        assertThat(snapshot.stories()).hasSize(1);
        assertThat(snapshot.stories().get(0).priority()).isEqualTo("low");
    }

    @Test
    void getGraph_intraEpicDependency_appearsInDependenciesNotExternalBlockers() {
        EpicResponse epic = makeEpic("https://github.com/test/graph-intra-dep.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse blocking = makeTask(story.id(), "Blocking");
        TaskResponse blocked = makeTask(story.id(), "Blocked");
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        assertThat(snapshot.dependencies()).hasSize(1);
        assertThat(snapshot.dependencies().get(0).blockingItemId()).isEqualTo(blocking.id());
        assertThat(snapshot.dependencies().get(0).blockedItemId()).isEqualTo(blocked.id());
        assertThat(snapshot.externalBlockers()).isEmpty();
    }

    @Test
    void getGraph_blockerInDifferentEpic_appearsInExternalBlockersNotDependencies() {
        EpicResponse epicA = makeEpic("https://github.com/test/graph-external-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-external-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockingInB = makeTask(storyB.id(), "Blocking in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "task", blockedInA.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epicA.id());

        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.externalBlockers()).hasSize(1);
        var blocker = snapshot.externalBlockers().get(0);
        assertThat(blocker.itemType()).isEqualTo("task");
        assertThat(blocker.itemId()).isEqualTo(blockingInB.id());
        assertThat(blocker.title()).isEqualTo("Blocking in B");
        assertThat(blocker.epicId()).isEqualTo(epicB.id());
        assertThat(blocker.epicTitle()).isEqualTo(epicB.title());
    }

    @Test
    void getGraph_blockedInDifferentEpic_appearsInExternalBlockersNotDependencies() {
        // Mirror of getGraph_blockerInDifferentEpic_... above but with the inside/outside roles
        // swapped: an item INSIDE the requested Epic is the *blocking* side, and the *blocked*
        // side lives in a different Epic. Exercises the `blockingInside && !blockedInside` branch
        // in getGraph, which the other external-blocker test above doesn't reach.
        EpicResponse epicA = makeEpic("https://github.com/test/graph-external-blocked-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockingInA = makeTask(storyA.id(), "Blocking in A");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-external-blocked-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockedInB = makeTask(storyB.id(), "Blocked in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInA.id(), "task", blockedInB.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epicA.id());

        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.externalBlockers()).hasSize(1);
        var blocker = snapshot.externalBlockers().get(0);
        assertThat(blocker.itemType()).isEqualTo("task");
        assertThat(blocker.itemId()).isEqualTo(blockedInB.id());
        assertThat(blocker.title()).isEqualTo("Blocked in B");
        assertThat(blocker.epicId()).isEqualTo(epicB.id());
        assertThat(blocker.epicTitle()).isEqualTo(epicB.title());
    }

    @Test
    void getGraph_blockerInDifferentEpic_externalBlockerHasDirectionBlockingAndInternalItemId() {
        // Same fixture as getGraph_blockerInDifferentEpic_appearsInExternalBlockersNotDependencies
        // above, extended to assert the new direction/internalItemId fields (Decision 3): the
        // external item BLOCKS the in-Epic item, so direction is BLOCKING and internalItemId
        // points back at the in-Epic (blocked) Task.
        EpicResponse epicA = makeEpic("https://github.com/test/graph-direction-blocking-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-direction-blocking-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockingInB = makeTask(storyB.id(), "Blocking in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "task", blockedInA.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epicA.id());

        assertThat(snapshot.externalBlockers()).hasSize(1);
        var blocker = snapshot.externalBlockers().get(0);
        assertThat(blocker.direction()).isEqualTo(BlockerDirection.BLOCKING);
        assertThat(blocker.internalItemId()).isEqualTo(blockedInA.id());
    }

    @Test
    void getGraph_blockedInDifferentEpic_externalBlockerHasDirectionBlockedAndInternalItemId() {
        // Same fixture as getGraph_blockedInDifferentEpic_appearsInExternalBlockersNotDependencies
        // above, extended to assert the new direction/internalItemId fields (Decision 3): the
        // in-Epic item BLOCKS the external item, so from the external item's perspective direction
        // is BLOCKED and internalItemId points back at the in-Epic (blocking) Task.
        EpicResponse epicA = makeEpic("https://github.com/test/graph-direction-blocked-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockingInA = makeTask(storyA.id(), "Blocking in A");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-direction-blocked-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockedInB = makeTask(storyB.id(), "Blocked in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInA.id(), "task", blockedInB.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epicA.id());

        assertThat(snapshot.externalBlockers()).hasSize(1);
        var blocker = snapshot.externalBlockers().get(0);
        assertThat(blocker.direction()).isEqualTo(BlockerDirection.BLOCKED);
        assertThat(blocker.internalItemId()).isEqualTo(blockingInA.id());
    }

    @Test
    void getGraph_externalBlockerTouchingMultipleInternalItems_eachRefHasDistinctInternalItemId() {
        // The same external Task blocks two different in-Epic Tasks — Decision 4's dedup
        // responsibility lives client-side, so the service must still emit one ExternalBlockerRef
        // per edge here, each carrying the specific in-Epic item it touches.
        EpicResponse epicA = makeEpic("https://github.com/test/graph-multi-internal-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA1 = makeTask(storyA.id(), "Blocked in A 1");
        TaskResponse blockedInA2 = makeTask(storyA.id(), "Blocked in A 2");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-multi-internal-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockingInB = makeTask(storyB.id(), "Blocking in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "task", blockedInA1.id()));
        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "task", blockedInA2.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epicA.id());

        assertThat(snapshot.externalBlockers()).hasSize(2);
        assertThat(snapshot.externalBlockers())
                .allMatch(b -> b.itemId().equals(blockingInB.id()))
                .allMatch(b -> b.direction() == BlockerDirection.BLOCKING);
        assertThat(snapshot.externalBlockers())
                .extracting(ExternalBlockerRef::internalItemId)
                .containsExactlyInAnyOrder(blockedInA1.id(), blockedInA2.id());
    }

    @Test
    void getGraph_internalPath_blockerInDifferentEpic_externalBlockerHasDirectionAndInternalItemId() {
        // Exercises the internal 3-arg getGraph(epicId, runId, softwareProjectId) overload used by
        // InternalRunController/get-roadmap-graph — assemble() is shared with the public path
        // above, so this proves that sharing actually threads direction/internalItemId through
        // both paths rather than assuming it.
        EpicResponse epicA = makeEpic("https://github.com/test/graph-internal-direction-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-internal-direction-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockingInB = makeTask(storyB.id(), "Blocking in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "task", blockedInA.id()));
        UUID runId = UUID.randomUUID();

        RoadmapGraphSnapshot snapshot =
                graphService.getGraph(epicA.id(), runId, epicA.softwareProject().id());

        assertThat(snapshot.externalBlockers()).hasSize(1);
        var blocker = snapshot.externalBlockers().get(0);
        assertThat(blocker.direction()).isEqualTo(BlockerDirection.BLOCKING);
        assertThat(blocker.internalItemId()).isEqualTo(blockedInA.id());
    }

    @Test
    void getGraph_externalBlockerIsStory_resolvesStoryTitleAndOwningEpic() {
        // The only prior external-blocker coverage used Task items; this exercises
        // toExternalBlockerRef's `type == BlockableItemType.story` branch, which resolves the
        // Story's own Epic directly rather than via an intermediate Story lookup.
        EpicResponse epicA = makeEpic("https://github.com/test/graph-external-story-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-external-story-b.git");
        StoryResponse blockingStoryB = makeStory(epicB.id(), "Blocking Story B");

        dependencyService.create(new CreateDependencyRequest("story", blockingStoryB.id(), "task", blockedInA.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epicA.id());

        assertThat(snapshot.externalBlockers()).hasSize(1);
        var blocker = snapshot.externalBlockers().get(0);
        assertThat(blocker.itemType()).isEqualTo("story");
        assertThat(blocker.itemId()).isEqualTo(blockingStoryB.id());
        assertThat(blocker.title()).isEqualTo("Blocking Story B");
        assertThat(blocker.epicId()).isEqualTo(epicB.id());
        assertThat(blocker.epicTitle()).isEqualTo(epicB.title());
    }

    @Test
    void getGraph_externalBlocker_checksOrgAccessForResolvedItem() {
        // The mocked AuthorizationService is a no-op by default (see WorkItemDependencyServiceTest
        // for the same pattern) so the happy-path tests above pass whether or not
        // toExternalBlockerRef's checkOrgAccess call exists at all. This test proves the call is
        // actually wired up — deleting it would still pass every other test in this class but
        // fail this `verify`.
        EpicResponse epicA = makeEpic("https://github.com/test/graph-external-authcheck-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-external-authcheck-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockingInB = makeTask(storyB.id(), "Blocking in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "task", blockedInA.id()));
        // create() itself calls checkOrgAccess on both endpoints — clear that invocation so the
        // verify below isolates the call toExternalBlockerRef makes during getGraph.
        clearInvocations(authService);

        graphService.getGraph(epicA.id());

        verify(authService).checkOrgAccess("task", blockingInB.id());
    }

    @Test
    void getGraph_externalBlockerFailsOrgCheck_propagatesForbidden() {
        // Simulates a future path (bulk import, admin ownership-transfer, direct repository
        // writes — see toExternalBlockerRef's comment) that could insert a dependency edge whose
        // external endpoint is genuinely out of the caller's org. Proves the checkOrgAccess call
        // added in toExternalBlockerRef actually fails closed instead of only ever being a no-op.
        EpicResponse epicA = makeEpic("https://github.com/test/graph-external-forbidden-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/graph-external-forbidden-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockingInB = makeTask(storyB.id(), "Blocking in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "task", blockedInA.id()));

        doThrow(new ForbiddenException("org mismatch"))
                .when(authService)
                .checkOrgAccess(eq("task"), eq(blockingInB.id()));

        assertThatThrownBy(() -> graphService.getGraph(epicA.id())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getGraph_unknownEpic_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> graphService.getGraph(unknown))
                .isInstanceOf(com.choruskube.core.exception.NotFoundException.class);
    }

    // ── readiness (Decision 2) ────────────────────────────────────────────────

    @Test
    void getGraph_taskWithNoDependencyEdges_isReady() {
        EpicResponse epic = makeEpic("https://github.com/test/readiness-no-edges.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse task = makeTask(story.id(), "Task");

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        assertThat(snapshot.tasks()).hasSize(1);
        assertThat(snapshot.tasks().get(0).readiness()).isEqualTo(Readiness.READY);
        assertThat(snapshot.stories()).hasSize(1);
        assertThat(snapshot.stories().get(0).readiness()).isEqualTo(Readiness.READY);
    }

    @Test
    void getGraph_taskWithNonDoneBlocker_isBlocked() {
        EpicResponse epic = makeEpic("https://github.com/test/readiness-blocked.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse blocking = makeTask(story.id(), "Blocking");
        TaskResponse blocked = makeTask(story.id(), "Blocked");
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        TaskResponse blockedResponse = snapshot.tasks().stream()
                .filter(t -> t.id().equals(blocked.id()))
                .findFirst()
                .orElseThrow();
        assertThat(blockedResponse.readiness()).isEqualTo(Readiness.BLOCKED);
        TaskResponse blockingResponse = snapshot.tasks().stream()
                .filter(t -> t.id().equals(blocking.id()))
                .findFirst()
                .orElseThrow();
        assertThat(blockingResponse.readiness()).isEqualTo(Readiness.READY);
    }

    @Test
    void getGraph_blockerBecomesDone_flipsBlockedToReady() {
        EpicResponse epic = makeEpic("https://github.com/test/readiness-flip.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse blocking = makeTask(story.id(), "Blocking");
        TaskResponse blocked = makeTask(story.id(), "Blocked");
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        assertThat(graphService.getGraph(epic.id()).tasks().stream()
                        .filter(t -> t.id().equals(blocked.id()))
                        .findFirst()
                        .orElseThrow()
                        .readiness())
                .isEqualTo(Readiness.BLOCKED);

        markTaskDone(blocking.id());

        assertThat(graphService.getGraph(epic.id()).tasks().stream()
                        .filter(t -> t.id().equals(blocked.id()))
                        .findFirst()
                        .orElseThrow()
                        .readiness())
                .isEqualTo(Readiness.READY);
    }

    @Test
    void getGraph_storyBlockedByExternalTask_isBlocked() {
        EpicResponse epicA = makeEpic("https://github.com/test/readiness-external-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");

        EpicResponse epicB = makeEpic("https://github.com/test/readiness-external-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockingInB = makeTask(storyB.id(), "Blocking in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "story", storyA.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epicA.id());

        assertThat(snapshot.stories().get(0).readiness()).isEqualTo(Readiness.BLOCKED);
    }

    // ── transitive readiness (multi-step blocking chains) ─────────────────────

    @Test
    void getGraph_threeNodeChain_onlyRootUndone_tailIsBlocked() {
        EpicResponse epic = makeEpic("https://github.com/test/readiness-chain-root-undone.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse root = makeTask(story.id(), "Root");
        TaskResponse middle = makeTask(story.id(), "Middle");
        TaskResponse tail = makeTask(story.id(), "Tail");
        dependencyService.create(new CreateDependencyRequest("task", root.id(), "task", middle.id()));
        dependencyService.create(new CreateDependencyRequest("task", middle.id(), "task", tail.id()));

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        assertThat(readinessOf(snapshot, middle.id())).isEqualTo(Readiness.BLOCKED);
        assertThat(readinessOf(snapshot, tail.id())).isEqualTo(Readiness.BLOCKED);
        assertThat(readinessOf(snapshot, root.id())).isEqualTo(Readiness.READY);
    }

    @Test
    void getGraph_threeNodeChain_middleDoneRootUndone_tailStillBlocked() {
        // The core regression this feature exists to fix: marking the middle link done must not
        // flip the tail to READY while the root cause further upstream is still not done.
        EpicResponse epic = makeEpic("https://github.com/test/readiness-chain-middle-done.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse root = makeTask(story.id(), "Root");
        TaskResponse middle = makeTask(story.id(), "Middle");
        TaskResponse tail = makeTask(story.id(), "Tail");
        dependencyService.create(new CreateDependencyRequest("task", root.id(), "task", middle.id()));
        dependencyService.create(new CreateDependencyRequest("task", middle.id(), "task", tail.id()));

        markTaskDone(middle.id());
        // root is deliberately left undone.

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        assertThat(readinessOf(snapshot, tail.id())).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void getGraph_diamondDependency_isBlocked() {
        EpicResponse epic = makeEpic("https://github.com/test/readiness-diamond.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse blockerA = makeTask(story.id(), "Blocker A");
        TaskResponse blockerB = makeTask(story.id(), "Blocker B");
        TaskResponse blocked = makeTask(story.id(), "Blocked by both");
        dependencyService.create(new CreateDependencyRequest("task", blockerA.id(), "task", blocked.id()));
        dependencyService.create(new CreateDependencyRequest("task", blockerB.id(), "task", blocked.id()));

        markTaskDone(blockerB.id());
        // blockerA is deliberately left undone.

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        assertThat(readinessOf(snapshot, blocked.id())).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void getGraph_crossEpicBlocker_notWalkedPastExternalBlocker() {
        // An item's direct blocker is itself blocked by an item in a DIFFERENT Epic (Decision 2):
        // the walk is bounded to the requesting Epic, so it stops at the external blocker's own
        // status and does not follow that blocker's further upstream chain — the item renders
        // READY once the external blocker itself is done, even though something further upstream
        // of it (in the other Epic) is still not done.
        EpicResponse epicA = makeEpic("https://github.com/test/readiness-cross-epic-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/readiness-cross-epic-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse externalBlocker = makeTask(storyB.id(), "External Blocker in B");
        TaskResponse upstreamOfExternalBlocker = makeTask(storyB.id(), "Upstream of External Blocker in B");

        dependencyService.create(new CreateDependencyRequest("task", externalBlocker.id(), "task", blockedInA.id()));
        dependencyService.create(
                new CreateDependencyRequest("task", upstreamOfExternalBlocker.id(), "task", externalBlocker.id()));

        markTaskDone(externalBlocker.id());
        // upstreamOfExternalBlocker is deliberately left undone.

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epicA.id());

        assertThat(readinessOf(snapshot, blockedInA.id())).isEqualTo(Readiness.READY);
    }

    // ── recentRuns / totalRunCount (Decision 3) ───────────────────────────────

    @Test
    void getGraph_taskWithMoreThanFiveRuns_cappedRecentRunsWithCorrectTotalCount() {
        EpicResponse epic = makeEpic("https://github.com/test/history-cap.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse task = makeTask(story.id(), "Task");

        for (int i = 0; i < 7; i++) {
            TaskResponse started = taskService.start(task.id());
            markRunTerminal(started.latestRunId(), WorkflowRunStatus.failed);
            taskService.updateStatus(task.id(), com.choruskube.core.model.enums.WorkItemStatus.backlog, null, null);
        }

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        TaskResponse taskResponse = snapshot.tasks().get(0);
        assertThat(taskResponse.recentRuns()).hasSize(5);
        assertThat(taskResponse.totalRunCount()).isEqualTo(7);
    }

    @Test
    void getGraph_taskWithNoRuns_emptyRecentRunsAndZeroTotalCount() {
        EpicResponse epic = makeEpic("https://github.com/test/history-empty.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        makeTask(story.id(), "Task");

        RoadmapGraphSnapshot snapshot = graphService.getGraph(epic.id());

        TaskResponse taskResponse = snapshot.tasks().get(0);
        assertThat(taskResponse.recentRuns()).isEmpty();
        assertThat(taskResponse.totalRunCount()).isZero();
    }

    // ── internal path (agent-facing mirror, Decision 1/Decision 5) ────────────

    @Test
    void getGraphInternal_sameRunSoftwareProject_returnsSameShapeAsPublicPath() {
        EpicResponse epic = makeEpic("https://github.com/test/internal-graph-ok.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse task = makeTask(story.id(), "Task");
        UUID runId = UUID.randomUUID();

        RoadmapGraphSnapshot snapshot =
                graphService.getGraph(epic.id(), runId, epic.softwareProject().id());

        assertThat(snapshot.epic().id()).isEqualTo(epic.id());
        assertThat(snapshot.stories()).extracting(StoryResponse::id).containsExactly(story.id());
        assertThat(snapshot.tasks()).extracting(TaskResponse::id).containsExactly(task.id());
        assertThat(snapshot.tasks().get(0).readiness()).isEqualTo(Readiness.READY);
    }

    @Test
    void getGraphInternal_foreignSoftwareProject_throwsForbidden() {
        EpicResponse epic = makeEpic("https://github.com/test/internal-graph-foreign.git");
        UUID runId = UUID.randomUUID();
        UUID foreignProjectId = UUID.randomUUID();

        assertThatThrownBy(() -> graphService.getGraph(epic.id(), runId, foreignProjectId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getGraphInternal_unknownEpic_throwsNotFound() {
        assertThatThrownBy(() -> graphService.getGraph(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(com.choruskube.core.exception.NotFoundException.class);
    }

    @Test
    void getGraphInternal_externalBlocker_usesAssertSameOrgNotCheckOrgAccess() {
        // Mirrors getGraph_externalBlocker_checksOrgAccessForResolvedItem above, but for the
        // internal path: proves the internal graph read authorizes cross-epic references via
        // assertSameOrg (safe with no request-scoped tenant context) rather than checkOrgAccess.
        EpicResponse epicA = makeEpic("https://github.com/test/internal-external-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/internal-external-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse blockingInB = makeTask(storyB.id(), "Blocking in B");

        dependencyService.create(new CreateDependencyRequest("task", blockingInB.id(), "task", blockedInA.id()));
        org.mockito.Mockito.clearInvocations(authService);
        UUID runId = UUID.randomUUID();

        graphService.getGraph(epicA.id(), runId, epicA.softwareProject().id());

        org.mockito.Mockito.verify(authService).assertSameOrg("task", blockingInB.id(), "workflow_run", runId);
        org.mockito.Mockito.verify(authService, org.mockito.Mockito.never()).checkOrgAccess("task", blockingInB.id());
    }

    private void markRunTerminal(UUID runId, WorkflowRunStatus status) {
        WorkflowRun run = runRepo.findById(runId).orElseThrow();
        run.setStatus(status);
        runRepo.saveAndFlush(run);
    }

    private void markTaskDone(UUID taskId) {
        // Writes status directly rather than going through start()/complete(): some callers mark a
        // task done while its own blocker is still open, on purpose, to set up a multi-step chain —
        // a scenario TaskService.start() now rejects outright.
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(WorkItemStatus.done);
        taskRepo.saveAndFlush(t);
    }

    private static Readiness readinessOf(RoadmapGraphSnapshot snapshot, UUID taskId) {
        return snapshot.tasks().stream()
                .filter(t -> t.id().equals(taskId))
                .findFirst()
                .orElseThrow()
                .readiness();
    }

    private EpicResponse makeEpic(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }

    private StoryResponse makeStory(UUID epicId, String title) {
        return storyService.create(epicId, new StoryRequest(title, "Desc for " + title));
    }

    private TaskResponse makeTask(UUID storyId, String title) {
        return taskService.create(storyId, new TaskRequest(title, "Desc for " + title));
    }
}
