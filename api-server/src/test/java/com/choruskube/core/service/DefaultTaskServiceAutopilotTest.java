package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.ConflictException;
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
import java.time.Instant;
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

/**
 * The Autopilot start path. Deliberately NOT {@code @Transactional}, unlike its sibling {@link
 * DefaultTaskServiceTest}: this class drives {@code startForAutopilot} the way the tick does, as a
 * top-level transaction per call, and the properties it pins — that one failed start leaves its
 * neighbours intact, and that two concurrent starters produce exactly one run — are only
 * observable across real commit boundaries.
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

    private CommittedFixtureCleaner cleaner;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
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
     * A failed start costs only itself.
     *
     * <p>This used to be pinned the other way round, with {@code REQUIRES_NEW} and a caller
     * transaction wrapping both calls — because the Autopilot tick was one long transaction that
     * started several Tasks in a loop, and a failure joining it would have marked it rollback-only
     * and discarded the earlier start along with the tick's bookkeeping.
     *
     * <p>The tick is now four short transactions and calls this from none of them, so each start
     * is already top level and the propagation that made the old test necessary is gone. What
     * still has to hold is the property the old test was protecting: one failure in a pass leaves
     * the starts around it intact. That is what this drives, in the shape phase 3 actually uses.
     */
    @Test
    void startForAutopilot_failedStart_leavesAnEarlierStartInTheSamePassIntact() {
        GitRepo r = makeRepo("autopilot-independent-starts");
        StoryResponse story = makeStory(r.getId());
        TaskResponse ready = service.create(story.id(), new TaskRequest("Ready", "D"));
        TaskResponse blocker = service.create(story.id(), new TaskRequest("Blocker", "D"));
        TaskResponse blocked = service.create(story.id(), new TaskRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocker.id(), "task", blocked.id()));
        UUID autopilotId = makeAutopilot();

        List<String> failures = new ArrayList<>();
        service.startForAutopilot(ready.id(), autopilotId);
        try {
            service.startForAutopilot(blocked.id(), autopilotId);
        } catch (ConflictException e) {
            failures.add(e.getMessage());
        }

        assertThat(failures).hasSize(1);
        assertThat(service.get(ready.id()).status()).isEqualTo("in_progress");
        assertThat(service.get(blocked.id()).status()).isEqualTo("backlog");
    }

    /**
     * Correction 3: the Autopilot's tick lease serialises pass against pass, and the manual Start
     * path is outside it entirely — it takes no lease and no lock. Under READ COMMITTED both
     * callers can read the Task as {@code backlog}, both pass the status guard and both commit —
     * two agent containers for one Task. The row lock inside {@code startCore} closes it for BOTH
     * entry points, which is why this drives one of each concurrently.
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
        // Two statements rather than a save: AutopilotRepository deliberately exposes no entity
        // write path, so that the tick cannot grow one back. See its javadoc.
        UUID id = UUID.randomUUID();
        autopilotRepo.insertDefaults(id);
        autopilotRepo.engage(id, Instant.now());
        return cleaner.trackAutopilot(id);
    }

    private StoryResponse makeStory(UUID softwareProjectId) {
        EpicResponse epic = makeEpic(softwareProjectId, "Epic");
        return storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
    }

    private EpicResponse makeEpic(UUID softwareProjectId, String title) {
        EpicResponse epic = epicService.create(new EpicRequest(title, "Epic desc", null, softwareProjectId), null);
        cleaner.trackEpic(epic.id());
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
        cleaner.trackSoftwareProject(saved.getId());
        return saved;
    }

    /**
     * Undoes what the missing test transaction would have undone. Not tidiness: {@code
     * RoadmapTimelineServiceTest} asserts on an EMPTY roadmap and documents that it relies on no
     * test ever committing an Epic.
     */
    @AfterEach
    void removeEverythingThisTestCommitted() {
        cleaner.deleteAll();
    }
}
