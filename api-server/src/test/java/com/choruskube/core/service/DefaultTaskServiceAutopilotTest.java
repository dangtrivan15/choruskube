package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.Autopilot;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Autopilot start path. Deliberately NOT {@code @Transactional}, unlike its sibling {@link
 * DefaultTaskServiceTest}: {@code startForAutopilot} runs in its own {@code REQUIRES_NEW}
 * transaction, which takes a separate connection and therefore cannot see rows a test-managed,
 * roll-back-only transaction has written but not committed. Fixtures here must be committed for
 * the method under test to see them at all — and the transaction-isolation guarantee this class
 * exists to pin is only observable across a real commit boundary.
 *
 * <p>Committing means the usual rollback safety net is gone, so {@link
 * #removeEverythingThisTestCommitted()} does that job by hand — the shared container is one
 * database for the whole suite, and {@code RoadmapTimelineServiceTest} asserts on an empty
 * roadmap.
 */
public class DefaultTaskServiceAutopilotTest extends BaseTest {

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
    private AutopilotRepository autopilotRepo;

    @Autowired
    private WorkItemDependencyService dependencyService;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> epicIds = new ArrayList<>();
    private final List<UUID> autopilotIds = new ArrayList<>();
    private final List<UUID> repoIds = new ArrayList<>();

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
    void startForAutopilot_readyBacklogTask_startsRunAndStampsAttribution() {
        GitRepo r = makeRepo("autopilot-start");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        UUID autopilotId = makeAutopilot();

        TaskResponse started = service.startForAutopilot(task.id(), autopilotId);

        assertThat(started.status()).isEqualTo("in_progress");
        WorkflowRun run = runRepo.findById(started.latestRunId()).orElseThrow();
        assertThat(run.getTaskId()).isEqualTo(task.id());
        assertThat(run.getAutopilotId()).isEqualTo(autopilotId);
    }

    @Test
    void startForAutopilot_blockedTask_throwsConflict() {
        GitRepo r = makeRepo("autopilot-blocked");
        StoryResponse story = makeStory(r.getId());
        TaskResponse blocker = service.create(story.id(), new TaskRequest("Blocker", "D"));
        TaskResponse blocked = service.create(story.id(), new TaskRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocker.id(), "task", blocked.id()));

        assertThatThrownBy(() -> service.startForAutopilot(blocked.id(), makeAutopilot()))
                .isInstanceOf(ConflictException.class);

        assertThat(service.get(blocked.id()).status()).isEqualTo("backlog");
    }

    @Test
    void startForAutopilot_crossEpicBlockerAlreadyDone_startsWithoutARequestContext() {
        // The case the Autopilot exists to handle, and the one the request-scoped readiness path
        // cannot serve: the Task's Epic carries a cross-Epic dependency edge, so assembling its
        // readiness has to resolve a blocker living outside the Epic. On the public path that
        // resolution goes through checkOrgAccess, which reads a tenant context no timer thread has.
        GitRepo r = makeRepo("autopilot-cross-epic");
        EpicResponse blockerEpic = makeEpic(r.getId(), "Blocker Epic");
        StoryResponse blockerStory = storyService.create(blockerEpic.id(), new StoryRequest("Blocker Story", "D"));
        TaskResponse blockerTask = service.create(blockerStory.id(), new TaskRequest("Blocker Task", "D"));

        EpicResponse epic = makeEpic(r.getId(), "Epic");
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "D"));
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blockerTask.id(), "task", task.id()));
        finishTask(blockerTask.id());

        TaskResponse started = service.startForAutopilot(task.id(), makeAutopilot());

        assertThat(started.status()).isEqualTo("in_progress");
    }

    /**
     * Correction 1: {@code REQUIRES_NEW} is load-bearing, not decoration. The Autopilot tick is
     * itself transactional and starts several Tasks in a loop, catching failures to count them.
     * Under the default {@code REQUIRED} propagation the failing start would join — and mark
     * rollback-only — the tick's own transaction, so the tick's commit would throw {@code
     * UnexpectedRollbackException}, discarding its bookkeeping AND the start that had already
     * succeeded in the same loop.
     */
    @Test
    void startForAutopilot_failedStart_doesNotDiscardAnEarlierStartInTheSameCallerTransaction() {
        GitRepo r = makeRepo("autopilot-requires-new");
        StoryResponse story = makeStory(r.getId());
        TaskResponse ready = service.create(story.id(), new TaskRequest("Ready", "D"));
        TaskResponse blocker = service.create(story.id(), new TaskRequest("Blocker", "D"));
        TaskResponse blocked = service.create(story.id(), new TaskRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocker.id(), "task", blocked.id()));
        UUID autopilotId = makeAutopilot();

        List<String> failures = new ArrayList<>();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            service.startForAutopilot(ready.id(), autopilotId);
            try {
                service.startForAutopilot(blocked.id(), autopilotId);
            } catch (ConflictException e) {
                failures.add(e.getMessage());
            }
        });

        assertThat(failures).hasSize(1);
        assertThat(service.get(ready.id()).status()).isEqualTo("in_progress");
        assertThat(service.get(blocked.id()).status()).isEqualTo("backlog");
    }

    /**
     * Correction 3: the Autopilot's advisory lock is on {@code autopilot.id} and serialises tick
     * against tick only, while the manual Start path takes no lock at all. Under READ COMMITTED
     * both callers can read the Task as {@code backlog}, both pass the status guard and both
     * commit — two agent containers for one Task. The row lock inside {@code startCore} closes it
     * for BOTH entry points, which is why this drives one of each concurrently.
     */
    @Test
    void concurrentManualAndAutopilotStart_produceExactlyOneRun() throws Exception {
        GitRepo r = makeRepo("autopilot-race");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("Contended", "D"));
        UUID autopilotId = makeAutopilot();

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> outcomes;
        try {
            List<Future<Throwable>> futures = pool.invokeAll(List.of(
                    attempt(startLine, () -> service.start(task.id())),
                    attempt(startLine, () -> service.startForAutopilot(task.id(), autopilotId))));
            outcomes = new ArrayList<>();
            for (Future<Throwable> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes.stream().filter(Objects::isNull).count())
                .as("exactly one starter should win")
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(Objects::nonNull).toList())
                .as("the loser is rejected by the status guard, not by a database error")
                .allMatch(ConflictException.class::isInstance);
        assertThat(runRepo.findByTaskIdOrderByCreatedAtDesc(task.id(), PageRequest.of(0, 10))
                        .getTotalElements())
                .isEqualTo(1L);
    }

    private static Callable<Throwable> attempt(CyclicBarrier startLine, Runnable action) {
        return () -> {
            startLine.await(30, TimeUnit.SECONDS);
            try {
                action.run();
                return null;
            } catch (Throwable t) {
                return t;
            }
        };
    }

    /** Drives a Task to {@code done} so it stops blocking — the whole start path, not a direct write. */
    private void finishTask(UUID taskId) {
        TaskResponse started = service.start(taskId);
        WorkflowRun run = runRepo.findById(started.latestRunId()).orElseThrow();
        run.setStatus(com.choruskube.core.model.enums.WorkflowRunStatus.completed);
        runRepo.saveAndFlush(run);
        service.complete(taskId);
    }

    /**
     * workflow_run.autopilot_id carries a real FK to the autopilot table (V14), so attribution
     * cannot be stamped with an arbitrary UUID — the run's save fails at commit, not at the call.
     */
    private UUID makeAutopilot() {
        Autopilot autopilot = new Autopilot();
        autopilot.setEngaged(true);
        UUID id = autopilotRepo.saveAndFlush(autopilot).getId();
        autopilotIds.add(id);
        return id;
    }

    private StoryResponse makeStory(UUID softwareProjectId) {
        EpicResponse epic = makeEpic(softwareProjectId, "Epic");
        return storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
    }

    private EpicResponse makeEpic(UUID softwareProjectId, String title) {
        EpicResponse epic = epicService.create(new EpicRequest(title, "Epic desc", null, softwareProjectId), null);
        epicIds.add(epic.id());
        return epic;
    }

    private GitRepo makeRepo(String slug) {
        // These rows commit, so the URL — which is unique per repo — carries a nonce.
        String url = "https://github.com/test/" + slug + "-"
                + UUID.randomUUID().toString().substring(0, 8) + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        GitRepo saved = gitRepoRepo.save(r);
        repoIds.add(saved.getId());
        return saved;
    }

    /**
     * Rolls back by hand what the missing test transaction would have rolled back for us. This is
     * not tidiness: {@code RoadmapTimelineServiceTest} asserts on an EMPTY roadmap and documents
     * that it relies on no test ever committing an Epic. Leaving these rows behind fails that
     * class, in a different package, depending on execution order.
     */
    @AfterEach
    void removeEverythingThisTestCommitted() {
        if (!epicIds.isEmpty()) {
            Object[] epics = epicIds.toArray();
            String tasksUnderEpics = "SELECT t.id FROM task t JOIN story s ON t.story_id = s.id WHERE s.epic_id IN ("
                    + placeholders(epicIds) + ")";
            jdbc.update(
                    "DELETE FROM work_item_dependency WHERE blocking_item_id IN (" + tasksUnderEpics
                            + ") OR blocked_item_id IN (" + tasksUnderEpics + ")",
                    concat(epics, epics));
            // workflow_run.task_id is a plain FK, so runs must go before the task tree; the Epic
            // delete then cascades story and task (V2).
            jdbc.update("DELETE FROM workflow_run WHERE task_id IN (" + tasksUnderEpics + ")", epics);
            jdbc.update("DELETE FROM epic WHERE id IN (" + placeholders(epicIds) + ")", epics);
        }
        if (!autopilotIds.isEmpty()) {
            jdbc.update(
                    "DELETE FROM autopilot WHERE id IN (" + placeholders(autopilotIds) + ")", autopilotIds.toArray());
        }
        if (!repoIds.isEmpty()) {
            // git_repo.id -> software_project.id is ON DELETE CASCADE, so one delete covers both.
            jdbc.update("DELETE FROM software_project WHERE id IN (" + placeholders(repoIds) + ")", repoIds.toArray());
        }
        epicIds.clear();
        autopilotIds.clear();
        repoIds.clear();
    }

    private static String placeholders(List<UUID> ids) {
        return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] all = new Object[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
