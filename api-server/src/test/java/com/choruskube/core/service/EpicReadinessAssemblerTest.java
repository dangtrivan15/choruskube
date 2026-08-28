package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.ExternalBlockerRef;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct coverage of {@link EpicReadinessAssembler} in isolation (extracted from {@code
 * DefaultRoadmapGraphService}), independent of the graph/list endpoints that call
 * it. {@link RoadmapGraphServiceTest} already exercises this transitively through the graph
 * endpoint; this class is the one place that pins the collaborator's own contract (readiness,
 * dependency edges, external blockers) so callers can't silently drift.
 */
@Transactional
public class EpicReadinessAssemblerTest extends BaseTest {

    @Autowired
    private EpicReadinessAssembler assembler;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private WorkItemDependencyService dependencyService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private StoryRepository storyRepo;

    @Autowired
    private EpicRepository epicRepo;

    @Autowired
    private WorkItemDependencyRepository dependencyRepo;

    private final AuthorizationService probeAuthService = Mockito.mock(AuthorizationService.class);

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @Test
    void assemble_noIncomingEdges_isReady() {
        EpicResponse epic = makeEpic("https://github.com/test/assembler-no-edges.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse task = makeTask(story.id(), "Task");

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epic.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(task.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.readinessById().get(story.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.dependencies()).isEmpty();
        assertThat(assembly.externalBlockers()).isEmpty();
    }

    @Test
    void assemble_directBlockerNotDone_isBlocked() {
        EpicResponse epic = makeEpic("https://github.com/test/assembler-direct-blocker.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse blocking = makeTask(story.id(), "Blocking");
        TaskResponse blocked = makeTask(story.id(), "Blocked");
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epic.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(blocked.id())).isEqualTo(Readiness.BLOCKED);
        assertThat(assembly.readinessById().get(blocking.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.dependencies()).hasSize(1);
    }

    @Test
    void assemble_threeNodeChain_middleDoneRootUndone_tailStillBlocked() {
        // The core regression the multi-step blocking chain feature fixes: marking the middle
        // link done must not flip the tail to READY while the root cause further upstream is
        // still not done.
        EpicResponse epic = makeEpic("https://github.com/test/assembler-chain.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse root = makeTask(story.id(), "Root");
        TaskResponse middle = makeTask(story.id(), "Middle");
        TaskResponse tail = makeTask(story.id(), "Tail");
        dependencyService.create(new CreateDependencyRequest("task", root.id(), "task", middle.id()));
        dependencyService.create(new CreateDependencyRequest("task", middle.id(), "task", tail.id()));
        markDone(middle.id());
        // root is deliberately left undone.

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epic.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(tail.id())).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void assemble_externalBlocker_gatesReadinessAtOneHopOnly() {
        // The blocked item's direct blocker lives in a different Epic and is itself blocked by
        // something further upstream in that OTHER Epic — the walk here is bounded to the
        // requested Epic's own candidate set, so only the external blocker's own
        // (done) status gates readiness; its further upstream chain is not followed.
        EpicResponse epicA = makeEpic("https://github.com/test/assembler-external-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/assembler-external-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse externalBlocker = makeTask(storyB.id(), "External Blocker in B");
        TaskResponse upstreamOfExternalBlocker = makeTask(storyB.id(), "Upstream of External Blocker in B");

        dependencyService.create(new CreateDependencyRequest("task", externalBlocker.id(), "task", blockedInA.id()));
        dependencyService.create(
                new CreateDependencyRequest("task", upstreamOfExternalBlocker.id(), "task", externalBlocker.id()));
        markDone(externalBlocker.id());
        // upstreamOfExternalBlocker is deliberately left undone.

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epicA.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(blockedInA.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.externalBlockers()).hasSize(1);
        ExternalBlockerRef blocker = assembly.externalBlockers().get(0);
        assertThat(blocker.itemId()).isEqualTo(externalBlocker.id());
        assertThat(blocker.epicId()).isEqualTo(epicB.id());
    }

    @Test
    void assemble_externalBlockerStillNotDone_isBlocked() {
        EpicResponse epicA = makeEpic("https://github.com/test/assembler-external-blocked-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/assembler-external-blocked-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse externalBlocker = makeTask(storyB.id(), "External Blocker in B");

        dependencyService.create(new CreateDependencyRequest("task", externalBlocker.id(), "task", blockedInA.id()));

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epicA.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(blockedInA.id())).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void loadEpicCandidates_includesEveryStoryAndTaskUnderTheEpic() {
        EpicResponse epic = makeEpic("https://github.com/test/assembler-load-candidates.git");
        StoryResponse story1 = makeStory(epic.id(), "Story 1");
        StoryResponse story2 = makeStory(epic.id(), "Story 2");
        TaskResponse t1 = makeTask(story1.id(), "T1");
        TaskResponse t2 = makeTask(story2.id(), "T2");

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epic.id());

        assertThat(candidates.candidateIds())
                .containsExactlyInAnyOrder(epic.id(), story1.id(), story2.id(), t1.id(), t2.id());
        assertThat(candidates.tasksByStoryId().get(story1.id()))
                .extracting(Task::getId)
                .containsExactly(t1.id());
        assertThat(candidates.tasksByStoryId().get(story2.id()))
                .extracting(Task::getId)
                .containsExactly(t2.id());
    }

    @Test
    void assemble_taskUnderBlockedStory_isBlocked() {
        EpicResponse epic = makeEpic("https://github.com/test/assembler-story-cascade.git");
        StoryResponse blockingStory = makeStory(epic.id(), "Blocking story");
        StoryResponse blockedStory = makeStory(epic.id(), "Blocked story");
        makeTask(blockingStory.id(), "Prerequisite"); // left undone, so blockingStory is not done
        TaskResponse taskUnderBlocked = makeTask(blockedStory.id(), "Inherits the block");
        dependencyService.create(new CreateDependencyRequest("story", blockingStory.id(), "story", blockedStory.id()));

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epic.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(blockedStory.id())).isEqualTo(Readiness.BLOCKED);
        assertThat(assembly.readinessById().get(taskUnderBlocked.id())).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void assemble_taskUnderBlockedEpic_isBlocked() {
        EpicResponse blockingEpic = makeEpic("https://github.com/test/assembler-epic-cascade-a.git");
        StoryResponse blockingStory = makeStory(blockingEpic.id(), "Prerequisite story");
        makeTask(blockingStory.id(), "Prerequisite"); // left undone

        EpicResponse blockedEpic = makeEpic("https://github.com/test/assembler-epic-cascade-b.git");
        StoryResponse story = makeStory(blockedEpic.id(), "Story");
        TaskResponse task = makeTask(story.id(), "Task");
        dependencyService.create(new CreateDependencyRequest("epic", blockingEpic.id(), "epic", blockedEpic.id()));

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(blockedEpic.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(blockedEpic.id())).isEqualTo(Readiness.BLOCKED);
        assertThat(assembly.readinessById().get(story.id())).isEqualTo(Readiness.BLOCKED);
        assertThat(assembly.readinessById().get(task.id())).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void assemble_noEdges_containerAndWorkAllReady() {
        EpicResponse epic = makeEpic("https://github.com/test/assembler-cascade-clean.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse task = makeTask(story.id(), "Task");

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epic.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(epic.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.readinessById().get(story.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.readinessById().get(task.id())).isEqualTo(Readiness.READY);
    }

    @Test
    void assemble_blockingEpicAllTasksDone_dependentEpicIsReady() {
        EpicResponse blockingEpic = makeEpic("https://github.com/test/assembler-epic-done-a.git");
        StoryResponse blockingStory = makeStory(blockingEpic.id(), "Prerequisite story");
        TaskResponse prerequisite = makeTask(blockingStory.id(), "Prerequisite");
        markDone(prerequisite.id());

        EpicResponse blockedEpic = makeEpic("https://github.com/test/assembler-epic-done-b.git");
        StoryResponse story = makeStory(blockedEpic.id(), "Story");
        TaskResponse task = makeTask(story.id(), "Task");
        dependencyService.create(new CreateDependencyRequest("epic", blockingEpic.id(), "epic", blockedEpic.id()));

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(blockedEpic.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(blockedEpic.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.readinessById().get(task.id())).isEqualTo(Readiness.READY);
    }

    @Test
    void assemble_blockingEpicRolledOutWithUndoneTask_dependentEpicIsReady() {
        // Pins the rolled_out clause of epicStatus, which the rollup-only tests above never
        // touch: the Task is deliberately left undone, so if the stage check were dropped (or
        // came second instead of first) this would read BLOCKED, not READY.
        EpicResponse blockingEpic = makeEpic("https://github.com/test/assembler-epic-rolled-out-a.git");
        StoryResponse blockingStory = makeStory(blockingEpic.id(), "Prerequisite story");
        makeTask(blockingStory.id(), "Prerequisite"); // left undone
        epicService.updateStage(blockingEpic.id(), WorkItemStatus.rolled_out);

        EpicResponse blockedEpic = makeEpic("https://github.com/test/assembler-epic-rolled-out-b.git");
        StoryResponse story = makeStory(blockedEpic.id(), "Story");
        TaskResponse task = makeTask(story.id(), "Task");
        dependencyService.create(new CreateDependencyRequest("epic", blockingEpic.id(), "epic", blockedEpic.id()));

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(blockedEpic.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(blockedEpic.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.readinessById().get(task.id())).isEqualTo(Readiness.READY);
    }

    @Test
    void assemble_blockingStoryRolledOutWithUndoneTask_dependentIsReady() {
        // Story-tier equivalent of the Epic rolled_out test above: pins storyStatus's rolled_out
        // clause via loadEpicCandidates (both Stories are under the same Epic, so this is the
        // in-candidate-set path, not resolveExternalBlocker's story branch).
        EpicResponse epic = makeEpic("https://github.com/test/assembler-story-rolled-out.git");
        StoryResponse blockingStory = makeStory(epic.id(), "Blocking story");
        makeTask(blockingStory.id(), "Prerequisite"); // left undone
        storyService.updateStage(blockingStory.id(), WorkItemStatus.rolled_out);

        StoryResponse blockedStory = makeStory(epic.id(), "Blocked story");
        TaskResponse task = makeTask(blockedStory.id(), "Task");
        dependencyService.create(new CreateDependencyRequest("story", blockingStory.id(), "story", blockedStory.id()));

        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epic.id());
        EpicReadinessAssembler.Assembly assembly = assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);

        assertThat(assembly.readinessById().get(blockedStory.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.readinessById().get(task.id())).isEqualTo(Readiness.READY);
    }

    // ── ReadinessAuthMode: which authorization path a cross-Epic blocker is resolved through ──
    // Core's AlwaysAllowAuthorizationStrategy no-ops both calls, so the choice is invisible from
    // behaviour alone. These three tests are the only place it is pinned — and the reason it
    // matters is that checkOrgAccess reads a request-scoped tenant context, which the Autopilot's
    // timer thread does not have. A probe assembler with a mocked AuthorizationService is used
    // rather than a bean override so the rest of the context keeps its real collaborator.

    @Test
    void assemble_publicMode_resolvesExternalBlockersThroughTheRequestScopedCheck() {
        CrossEpicFixture f = crossEpicFixture("assembler-mode-public");

        assembleWith(f, ReadinessAuthMode.PUBLIC, null);

        Mockito.verify(probeAuthService).checkOrgAccess("task", f.externalBlockerId());
        Mockito.verify(probeAuthService, Mockito.never())
                .assertSameOrg(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any());
    }

    @Test
    void assemble_internalRunMode_resolvesExternalBlockersAgainstTheCallingRun() {
        CrossEpicFixture f = crossEpicFixture("assembler-mode-internal");
        UUID runId = UUID.randomUUID();

        assembleWith(f, ReadinessAuthMode.INTERNAL_RUN, runId);

        Mockito.verify(probeAuthService).assertSameOrg("task", f.externalBlockerId(), "workflow_run", runId);
        Mockito.verify(probeAuthService, Mockito.never())
                .checkOrgAccess(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void assemble_autopilotMode_resolvesExternalBlockersAgainstTheAutopilotNotTheRequestContext() {
        CrossEpicFixture f = crossEpicFixture("assembler-mode-autopilot");
        UUID autopilotId = UUID.randomUUID();

        assembleWith(f, ReadinessAuthMode.AUTOPILOT, autopilotId);

        Mockito.verify(probeAuthService).assertSameOrg("task", f.externalBlockerId(), "autopilot", autopilotId);
        Mockito.verify(probeAuthService, Mockito.never())
                .checkOrgAccess(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    // ── isStartable: the one shared "ready to start" predicate ───────────────────

    @Test
    void isStartable_onlyABacklogTaskCounts_evenThoughFinishedWorkIsAlsoReady() {
        EpicResponse epic = makeEpic("https://github.com/test/assembler-startable-status.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse waiting = makeTask(story.id(), "Waiting");
        TaskResponse started = makeTask(story.id(), "Started");
        TaskResponse finished = makeTask(story.id(), "Finished");
        markInProgress(started.id());
        markDone(finished.id());

        EpicReadinessAssembler.Assembly assembly = assembleFor(epic.id());

        // The trap this predicate exists to close: readiness is a statement about an item's
        // dependencies, so work that is already under way or finished is READY too.
        assertThat(assembly.readinessById().get(started.id())).isEqualTo(Readiness.READY);
        assertThat(assembly.readinessById().get(finished.id())).isEqualTo(Readiness.READY);

        assertThat(EpicReadinessAssembler.isStartable(entity(waiting.id()), assembly))
                .isTrue();
        assertThat(EpicReadinessAssembler.isStartable(entity(started.id()), assembly))
                .isFalse();
        assertThat(EpicReadinessAssembler.isStartable(entity(finished.id()), assembly))
                .isFalse();
    }

    @Test
    void isStartable_isFalseForABlockedBacklogTask() {
        EpicResponse epic = makeEpic("https://github.com/test/assembler-startable-blocked.git");
        StoryResponse story = makeStory(epic.id(), "Story");
        TaskResponse blocker = makeTask(story.id(), "Blocker");
        TaskResponse blocked = makeTask(story.id(), "Blocked");
        dependencyService.create(new CreateDependencyRequest("task", blocker.id(), "task", blocked.id()));

        EpicReadinessAssembler.Assembly assembly = assembleFor(epic.id());

        assertThat(EpicReadinessAssembler.isStartable(entity(blocker.id()), assembly))
                .isTrue();
        assertThat(EpicReadinessAssembler.isStartable(entity(blocked.id()), assembly))
                .isFalse();
    }

    private EpicReadinessAssembler.Assembly assembleFor(UUID epicId) {
        EpicReadinessAssembler.EpicCandidates candidates = assembler.loadEpicCandidates(epicId);
        return assembler.assemble(
                candidates.candidateIds(),
                candidates.statusById(),
                candidates.parentOf(),
                ReadinessAuthMode.PUBLIC,
                null);
    }

    private Task entity(UUID taskId) {
        return taskRepo.findById(taskId).orElseThrow();
    }

    private record CrossEpicFixture(UUID epicId, UUID externalBlockerId) {}

    private CrossEpicFixture crossEpicFixture(String slug) {
        EpicResponse epicA = makeEpic("https://github.com/test/" + slug + "-a.git");
        StoryResponse storyA = makeStory(epicA.id(), "Story A");
        TaskResponse blockedInA = makeTask(storyA.id(), "Blocked in A");

        EpicResponse epicB = makeEpic("https://github.com/test/" + slug + "-b.git");
        StoryResponse storyB = makeStory(epicB.id(), "Story B");
        TaskResponse externalBlocker = makeTask(storyB.id(), "External Blocker in B");
        dependencyService.create(new CreateDependencyRequest("task", externalBlocker.id(), "task", blockedInA.id()));

        return new CrossEpicFixture(epicA.id(), externalBlocker.id());
    }

    private void assembleWith(CrossEpicFixture f, ReadinessAuthMode mode, UUID contextId) {
        EpicReadinessAssembler probe =
                new EpicReadinessAssembler(storyRepo, taskRepo, epicRepo, dependencyRepo, probeAuthService);
        EpicReadinessAssembler.EpicCandidates candidates = probe.loadEpicCandidates(f.epicId());
        probe.assemble(candidates.candidateIds(), candidates.statusById(), candidates.parentOf(), mode, contextId);
    }

    private void markInProgress(UUID taskId) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(WorkItemStatus.in_progress);
        taskRepo.saveAndFlush(t);
    }

    private void markDone(UUID taskId) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(WorkItemStatus.done);
        taskRepo.saveAndFlush(t);
    }

    private EpicResponse makeEpic(String url) {
        var r = new com.choruskube.core.model.GitRepo();
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
