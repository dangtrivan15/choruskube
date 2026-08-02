package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class DefaultTaskServiceTest extends BaseTest {

    @Autowired
    private TaskService service;

    @Autowired
    private StoryService storyService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private WorkItemDependencyService dependencyService;

    @Autowired
    private WorkItemDependencyRepository dependencyRepo;

    @Autowired
    private TaskRepository taskRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @BeforeEach
    void setUp() {
        WorkflowStub mockStub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
    }

    @Test
    void create_underStory_denormalizesSoftwareProjectIdFromAncestorEpic() {
        GitRepo r = makeRepo("https://github.com/test/task-one.git");
        StoryResponse story = makeStory(r.getId());

        TaskResponse task = service.create(story.id(), new TaskRequest("Task title", "Task desc"));

        assertThat(task.storyId()).isEqualTo(story.id());
        assertThat(task.softwareProject()).isNotNull();
        assertThat(task.softwareProject().id()).isEqualTo(r.getId());
        assertThat(task.status()).isEqualTo("backlog");
    }

    @Test
    void create_underUnknownStory_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(unknown, new TaskRequest("T", "D")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_withRunId_underStoryInRunsSoftwareProject_succeeds() {
        GitRepo r = makeRepo("https://github.com/test/task-runid-proj-ok.git");
        StoryResponse story = makeStory(r.getId());

        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"), UUID.randomUUID(), r.getId());

        assertThat(task.storyId()).isEqualTo(story.id());
        assertThat(task.title()).isEqualTo("T");
    }

    @Test
    void create_withRunId_underStoryOutsideRunsSoftwareProject_throwsForbidden() {
        GitRepo r1 = makeRepo("https://github.com/test/task-runid-proj-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/task-runid-proj-b.git");
        StoryResponse story = makeStory(r1.getId());

        // r2 is a real, different SoftwareProject in the same (only, OSS single-tenant) org — the
        // run's resolved software_project_id must still match the ancestor Epic's own, or the
        // Task would silently attach to a Story outside the run's actual target project.
        assertThatThrownBy(() -> service.create(story.id(), new TaskRequest("T", "D"), UUID.randomUUID(), r2.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void update_nonBacklogTask_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-update-blocked.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        TaskResponse started = service.start(task.id());
        assertThat(started.status()).isEqualTo("in_progress");

        assertThatThrownBy(() -> service.update(task.id(), new TaskRequest("New", "D")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_withDependencyEdge_alsoRemovesDependency() {
        GitRepo r = makeRepo("https://github.com/test/task-delete-dep.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse other = service.create(story.id(), new TaskRequest("Other", "D"));
        DependencyEdgeResponse edge =
                dependencyService.create(new CreateDependencyRequest("task", other.id(), "task", task.id()));

        service.delete(task.id());

        assertThat(dependencyRepo.findById(edge.id())).isEmpty();
    }

    @Test
    void delete_nonBacklogTask_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-delete-blocked.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        service.start(task.id());

        assertThatThrownBy(() -> service.delete(task.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void start_backlogTask_createsRunAndTransitionsToInProgress() {
        GitRepo r = makeRepo("https://github.com/test/task-start.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("Add health check", "Desc"));

        TaskResponse started = service.start(task.id());

        assertThat(started.status()).isEqualTo("in_progress");
        assertThat(started.latestRunId()).isNotNull();

        WorkflowRun run = runRepo.findById(started.latestRunId()).orElseThrow();
        assertThat(run.getTaskId()).isEqualTo(task.id());
    }

    @Test
    void start_taskWithOpenBlockers_stillSucceeds() {
        // Blocking is informational only (Decision 2) — Task.start() does not gate on open
        // blockers, matching how the Roadmap Graph View already lets a human start a blocked Task
        // today. This characterization test guards against that intentionally never changing here.
        GitRepo r = makeRepo("https://github.com/test/task-start-blocked.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse blocker = service.create(story.id(), new TaskRequest("Prerequisite", "D"));
        TaskResponse task = service.create(story.id(), new TaskRequest("Blocked task", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocker.id(), "task", task.id()));

        TaskResponse started = service.start(task.id());

        assertThat(started.status()).isEqualTo("in_progress");
        assertThat(started.latestRunId()).isNotNull();
    }

    @Test
    void start_whileActiveRunExists_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-start-active.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        service.start(task.id());

        // The most recent run is still pending (non-terminal) — re-triggering must be rejected.
        assertThatThrownBy(() -> service.start(task.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void start_afterMostRecentRunTerminal_restartsAndKeepsBothRunsInHistory() {
        GitRepo r = makeRepo("https://github.com/test/task-restart.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse afterFirstStart = service.start(task.id());
        markRunTerminal(afterFirstStart.latestRunId(), WorkflowRunStatus.failed);

        TaskResponse afterRestart = service.start(task.id());

        assertThat(afterRestart.latestRunId()).isNotEqualTo(afterFirstStart.latestRunId());

        Page<com.choruskube.core.dto.RunSummary> history = service.listRuns(task.id(), PageRequest.of(0, 20));
        assertThat(history.getContent()).hasSize(2);
        assertThat(history.getContent().get(0).id()).isEqualTo(afterRestart.latestRunId());
        assertThat(history.getContent().get(1).id()).isEqualTo(afterFirstStart.latestRunId());
    }

    @Test
    void complete_beforeMostRecentRunTerminal_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-complete-blocked.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        service.start(task.id());

        assertThatThrownBy(() -> service.complete(task.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void complete_afterMostRecentRunTerminal_transitionsToDone() {
        GitRepo r = makeRepo("https://github.com/test/task-complete-ok.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse started = service.start(task.id());
        markRunTerminal(started.latestRunId(), WorkflowRunStatus.completed);

        TaskResponse completed = service.complete(task.id());

        assertThat(completed.status()).isEqualTo("done");
    }

    @Test
    void complete_backlogTask_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-complete-backlog.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        assertThatThrownBy(() -> service.complete(task.id())).isInstanceOf(ConflictException.class);
    }

    // ── list(status, pageable): global Kanban board listing ──────────────────────
    // Note: unlike the cloud overlay, OSS core has no organization/tenant concept on Task at all
    // (no org_id column — see V2__work_hierarchy.sql) and its sole ScopeProvider implementation,
    // NoOpScopeProvider, always returns an unconditional conjunction (see NoOpScopeProviderTest) —
    // single-tenant sees everything. So there is no second org to construct here to prove
    // cross-tenant isolation; that guarantee is exercised in the cloud overlay's own
    // DefaultTaskServiceTest against its real (TenantContext-backed) ScopeProvider instead.

    @Test
    void list_unfiltered_returnsAllTasks() {
        GitRepo r = makeRepo("https://github.com/test/task-board-list-all.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse t1 = service.create(story.id(), new TaskRequest("T1", "D"));
        TaskResponse t2 = service.create(story.id(), new TaskRequest("T2", "D"));

        Page<TaskResponse> page =
                service.list(null, PageRequest.of(0, 20, Sort.by("createdAt").descending()));

        assertThat(page.getContent()).extracting(TaskResponse::id).contains(t1.id(), t2.id());
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void list_filteredByStatus_returnsOnlyMatchingRows() {
        GitRepo r = makeRepo("https://github.com/test/task-board-list-filtered.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse backlogTask = service.create(story.id(), new TaskRequest("Still Backlog", "D"));
        TaskResponse startedTask = service.create(story.id(), new TaskRequest("Started", "D"));
        service.start(startedTask.id());

        Page<TaskResponse> backlogPage = service.list(
                WorkItemStatus.backlog,
                PageRequest.of(0, 20, Sort.by("createdAt").descending()));
        Page<TaskResponse> inProgressPage = service.list(
                WorkItemStatus.in_progress,
                PageRequest.of(0, 20, Sort.by("createdAt").descending()));

        assertThat(backlogPage.getContent()).extracting(TaskResponse::id).contains(backlogTask.id());
        assertThat(backlogPage.getContent()).extracting(TaskResponse::id).doesNotContain(startedTask.id());
        assertThat(inProgressPage.getContent()).extracting(TaskResponse::id).contains(startedTask.id());
        assertThat(inProgressPage.getContent()).extracting(TaskResponse::id).doesNotContain(backlogTask.id());
    }

    @Test
    void list_readinessStaysNull_usesSharedSingleItemMapper() {
        // The plain listing endpoint deliberately reuses the shared single-item mapper (the same
        // one get()/create() use), NOT the Roadmap Graph View's EpicReadinessAssembler-backed path
        // — readiness is intentionally null here, unlike list(storyId).
        GitRepo r = makeRepo("https://github.com/test/task-board-list-readiness.git");
        StoryResponse story = makeStory(r.getId());
        service.create(story.id(), new TaskRequest("T", "D"));

        Page<TaskResponse> page =
                service.list(null, PageRequest.of(0, 20, Sort.by("createdAt").descending()));

        assertThat(page.getContent()).extracting(TaskResponse::readiness).containsOnlyNulls();
    }

    // ── updateStatus / updateStatusInternal (Decision 4) ─────────────────────────

    @Test
    void updateStatus_backlogToInProgress_delegatesToStartAndCreatesRun() {
        GitRepo r = makeRepo("https://github.com/test/task-updatestatus-start.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        TaskResponse updated =
                service.updateStatus(task.id(), com.choruskube.core.model.enums.WorkItemStatus.in_progress, null, null);

        assertThat(updated.status()).isEqualTo("in_progress");
        assertThat(updated.latestRunId()).isNotNull();
    }

    @Test
    void updateStatus_inProgressToDone_afterMostRecentRunTerminal_transitionsToDone() {
        GitRepo r = makeRepo("https://github.com/test/task-updatestatus-done.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse started = service.start(task.id());
        markRunTerminal(started.latestRunId(), WorkflowRunStatus.completed);

        TaskResponse updated = service.updateStatus(
                task.id(), com.choruskube.core.model.enums.WorkItemStatus.done, null, "agent-reported success");

        assertThat(updated.status()).isEqualTo("done");
    }

    @Test
    void updateStatus_inProgressToDone_withMismatchedRunId_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-updatestatus-done-mismatch.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse started = service.start(task.id());
        markRunTerminal(started.latestRunId(), WorkflowRunStatus.completed);

        assertThatThrownBy(() -> service.updateStatus(
                        task.id(), com.choruskube.core.model.enums.WorkItemStatus.done, UUID.randomUUID(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateStatus_inProgressToBacklog_afterMostRecentRunTerminal_reopens() {
        GitRepo r = makeRepo("https://github.com/test/task-updatestatus-reopen.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse started = service.start(task.id());
        markRunTerminal(started.latestRunId(), WorkflowRunStatus.failed);

        TaskResponse updated = service.updateStatus(
                task.id(), com.choruskube.core.model.enums.WorkItemStatus.backlog, null, "agent-reported failure");

        assertThat(updated.status()).isEqualTo("backlog");
    }

    @Test
    void updateStatus_inProgressToBacklog_beforeMostRecentRunTerminal_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-updatestatus-reopen-blocked.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        service.start(task.id());

        assertThatThrownBy(() -> service.updateStatus(
                        task.id(), com.choruskube.core.model.enums.WorkItemStatus.backlog, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateStatus_backlogToDone_rejectedAsInvalidTransition() {
        GitRepo r = makeRepo("https://github.com/test/task-updatestatus-invalid.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        assertThatThrownBy(() -> service.updateStatus(
                        task.id(), com.choruskube.core.model.enums.WorkItemStatus.done, null, null))
                .isInstanceOf(com.choruskube.core.exception.InvalidStatusTransitionException.class);
    }

    @Test
    void updateStatusInternal_inProgressToDone_succeeds() {
        GitRepo r = makeRepo("https://github.com/test/task-updatestatus-internal-done.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse started = service.start(task.id());
        markRunTerminal(started.latestRunId(), WorkflowRunStatus.completed);

        TaskResponse updated = service.updateStatusInternal(
                task.id(),
                com.choruskube.core.model.enums.WorkItemStatus.done,
                UUID.randomUUID(),
                r.getId(),
                null,
                "agent-reported success");

        assertThat(updated.status()).isEqualTo("done");
    }

    @Test
    void updateStatusInternal_backlogToInProgress_rejectedAsInvalidTransition() {
        // Unlike the public updateStatus, the internal/agent mirror does NOT support
        // backlog->in_progress: starting a Task creates a brand new workflow run, which isn't
        // this endpoint's job (an agent reports the outcome of a run it is already inside).
        GitRepo r = makeRepo("https://github.com/test/task-updatestatus-internal-invalid.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        assertThatThrownBy(() -> service.updateStatusInternal(
                        task.id(),
                        com.choruskube.core.model.enums.WorkItemStatus.in_progress,
                        UUID.randomUUID(),
                        r.getId(),
                        null,
                        null))
                .isInstanceOf(com.choruskube.core.exception.InvalidStatusTransitionException.class);
    }

    @Test
    void updateStatusInternal_outsideRunsSoftwareProject_throwsForbidden() {
        GitRepo r1 = makeRepo("https://github.com/test/task-updatestatus-internal-proj-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/task-updatestatus-internal-proj-b.git");
        StoryResponse story = makeStory(r1.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        service.start(task.id());

        assertThatThrownBy(() -> service.updateStatusInternal(
                        task.id(),
                        com.choruskube.core.model.enums.WorkItemStatus.done,
                        UUID.randomUUID(),
                        r2.getId(),
                        null,
                        null))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── readiness (Decision 1/2/3) — the flat list endpoint now populates the field the Roadmap
    // Graph View has always computed, via the same shared EpicReadinessAssembler ──────────────

    @Test
    void list_taskWithNoDependencyEdges_isReady() {
        GitRepo r = makeRepo("https://github.com/test/task-readiness-none.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        List<TaskResponse> result = service.list(story.id());

        assertThat(result).extracting(TaskResponse::readiness).containsExactly(Readiness.READY);
        assertThat(task.readiness()).isNull(); // create() itself still returns null (Decision 1 scopes list only)
    }

    @Test
    void list_taskBlockedByUnfinishedDependency_isBlocked() {
        GitRepo r = makeRepo("https://github.com/test/task-readiness-blocked.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse blocking = service.create(story.id(), new TaskRequest("Blocking", "D"));
        TaskResponse blocked = service.create(story.id(), new TaskRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        List<TaskResponse> result = service.list(story.id());

        assertThat(readinessOf(result, blocked.id())).isEqualTo(Readiness.BLOCKED);
        assertThat(readinessOf(result, blocking.id())).isEqualTo(Readiness.READY);
    }

    @Test
    void list_taskBlockedBySiblingStorysTask_underSameEpic_isBlocked() {
        // Decision 3: a Task list request is scoped to its OWNING EPIC's full Story/Task set, not
        // just the requested Story's own Tasks — a Task can be blocked by a Task under a
        // completely different sibling Story in the same Epic.
        GitRepo r = makeRepo("https://github.com/test/task-readiness-cross-story.git");
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
        StoryResponse blockerStory = storyService.create(epic.id(), new StoryRequest("Blocker Story", "D"));
        TaskResponse blockerTask = service.create(blockerStory.id(), new TaskRequest("Blocker Task", "D"));
        StoryResponse blockedStory = storyService.create(epic.id(), new StoryRequest("Blocked Story", "D"));
        TaskResponse blockedTask = service.create(blockedStory.id(), new TaskRequest("Blocked Task", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blockerTask.id(), "task", blockedTask.id()));

        List<TaskResponse> result = service.list(blockedStory.id());

        assertThat(readinessOf(result, blockedTask.id())).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void list_blockerBecomesDone_flipsBlockedTaskToReady() {
        GitRepo r = makeRepo("https://github.com/test/task-readiness-flip.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse blocking = service.create(story.id(), new TaskRequest("Blocking", "D"));
        TaskResponse blocked = service.create(story.id(), new TaskRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        assertThat(readinessOf(service.list(story.id()), blocked.id())).isEqualTo(Readiness.BLOCKED);

        markDone(blocking.id());

        assertThat(readinessOf(service.list(story.id()), blocked.id())).isEqualTo(Readiness.READY);
    }

    @Test
    void get_doesNotPopulateReadiness() {
        // Decision 1: only the flat list endpoints (and the Roadmap Graph View) compute
        // readiness — single-item reads are unaffected and keep returning null.
        GitRepo r = makeRepo("https://github.com/test/task-readiness-get-null.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse blocking = service.create(story.id(), new TaskRequest("Blocking", "D"));
        TaskResponse blocked = service.create(story.id(), new TaskRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        TaskResponse fetched = service.get(blocked.id());

        assertThat(fetched.readiness()).isNull();
    }

    private void markDone(UUID taskId) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(WorkItemStatus.done);
        taskRepo.saveAndFlush(t);
    }

    private static Readiness readinessOf(List<TaskResponse> tasks, UUID taskId) {
        return tasks.stream()
                .filter(t -> t.id().equals(taskId))
                .findFirst()
                .orElseThrow()
                .readiness();
    }

    @Test
    void listRuns_emptyHistory_returnsEmptyPage() {
        GitRepo r = makeRepo("https://github.com/test/task-empty-history.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        Page<com.choruskube.core.dto.RunSummary> history = service.listRuns(task.id(), PageRequest.of(0, 20));
        assertThat(history.getContent()).isEmpty();
    }

    private void markRunTerminal(UUID runId, WorkflowRunStatus status) {
        WorkflowRun run = runRepo.findById(runId).orElseThrow();
        run.setStatus(status);
        runRepo.saveAndFlush(run);
    }

    private StoryResponse makeStory(UUID softwareProjectId) {
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, softwareProjectId), null);
        return storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }
}
