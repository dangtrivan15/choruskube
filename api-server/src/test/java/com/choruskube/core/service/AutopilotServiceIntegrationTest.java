package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.dto.AutopilotStatusResponse;
import com.choruskube.core.dto.AutopilotTaskRef;
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
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tick against real Postgres, real readiness and a real {@code REQUIRES_NEW} start.
 *
 * <p>Deliberately NOT {@code @Transactional}. {@link TaskService#startForAutopilot} runs in its own
 * transaction on a separate connection, so it cannot see rows a roll-back-only test transaction
 * has written but not committed — its fixtures have to be committed for the tick to find them at
 * all. What that buys, beyond the unit suite: the {@code V15} settle column exists and round-trips,
 * {@code workflow_run.autopilot_id}'s foreign key is satisfied by a real row, and the advisory lock
 * plus the {@code REQUIRES_NEW} start genuinely coexist rather than blocking each other — the last
 * of which is invisible to mocks and would present as a hang, not a failure.
 *
 * <p>Committing means the usual rollback safety net is gone, so {@link
 * #removeEverythingThisTestCommitted()} does that job by hand; the shared container is one database
 * for the whole suite and {@code RoadmapTimelineServiceTest} asserts on an empty roadmap. Ids are
 * recorded at creation, so a test that fails halfway still cleans up what it made.
 */
public class AutopilotServiceIntegrationTest extends BaseTest {

    @Autowired
    private AutopilotService autopilotService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private WorkItemDependencyService dependencyService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private AutopilotRepository autopilotRepo;

    @Autowired
    private LockService lockService;

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
    void tick_readyTask_startsItAndStampsAttribution() {
        StoryResponse story = makeStory(makeRepo("autopilot-tick").getId());
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Ready", "D"));
        UUID autopilotId = engage();

        autopilotService.tick();

        assertThat(taskService.get(task.id()).status()).isEqualTo("in_progress");
        List<WorkflowRun> runs = runsFor(autopilotId);
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().getTaskId()).isEqualTo(task.id());
        assertThat(runs.getFirst().getStatus()).isEqualTo(WorkflowRunStatus.pending);
    }

    @Test
    void tick_blockedTask_isNeverStarted() {
        StoryResponse story = makeStory(makeRepo("autopilot-blocked").getId());
        TaskResponse blocker = taskService.create(story.id(), new TaskRequest("Blocker", "D"));
        TaskResponse blocked = taskService.create(story.id(), new TaskRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocker.id(), "task", blocked.id()));
        UUID autopilotId = engage();

        autopilotService.tick();

        assertThat(taskService.get(blocked.id()).status()).isEqualTo("backlog");
        assertThat(runsFor(autopilotId)).hasSize(1);
        assertThat(runsFor(autopilotId).getFirst().getTaskId()).isEqualTo(blocker.id());
    }

    @Test
    void tick_slotHeldByAPendingRun_startsNothingMore() {
        StoryResponse story = makeStory(makeRepo("autopilot-slots").getId());
        taskService.create(story.id(), new TaskRequest("First", "D"));
        taskService.create(story.id(), new TaskRequest("Second", "D"));
        UUID autopilotId = engage();

        autopilotService.tick();
        autopilotService.tick();

        assertThat(runsFor(autopilotId))
                .as("max_parallel defaults to 1 and the first run still holds the slot")
                .hasSize(1);
    }

    @Test
    void tick_parkedRunFreesItsSlot_soTheNextTaskStarts() {
        // Decision 2, and the whole point of the feature: a run waiting on a human dispatches no
        // workload, so holding its slot would idle the Autopilot for as long as the user is away.
        StoryResponse story = makeStory(makeRepo("autopilot-parked").getId());
        taskService.create(story.id(), new TaskRequest("First", "D"));
        taskService.create(story.id(), new TaskRequest("Second", "D"));
        UUID autopilotId = engage();

        autopilotService.tick();
        park(runsFor(autopilotId).getFirst(), WorkflowRunStatus.awaiting_human);
        autopilotService.tick();

        assertThat(runsFor(autopilotId)).hasSize(2);
        AutopilotStatusResponse status = autopilotService.getStatus();
        assertThat(status.inFlight())
                .as("only the second, pending run occupies a slot")
                .isEqualTo(1);
        assertThat(status.awaitingYou()).extracting(AutopilotTaskRef::status).containsExactly("awaiting_human");
    }

    @Test
    void tick_settleStampsTheRunAndIsIdempotent() {
        StoryResponse story = makeStory(makeRepo("autopilot-settle").getId());
        taskService.create(story.id(), new TaskRequest("Doomed", "D"));
        UUID autopilotId = engage();
        autopilotService.tick();
        WorkflowRun run = runsFor(autopilotId).getFirst();
        park(run, WorkflowRunStatus.failed);

        autopilotService.tick();
        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().getConsecutiveFailures())
                .isEqualTo(1);
        assertThat(runRepo.findById(run.getId()).orElseThrow().getAutopilotSettledAt())
                .as("V15's marker is what stops the next tick counting this run again")
                .isNotNull();

        autopilotService.tick();
        autopilotService.tick();

        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().getConsecutiveFailures())
                .as("a durable awaiting_retry/failed status must not be re-counted every tick")
                .isEqualTo(1);
        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().isEngaged())
                .isTrue();
    }

    @Test
    void tick_threeFailedRuns_disengageWithAReason() {
        StoryResponse story = makeStory(makeRepo("autopilot-breaker").getId());
        taskService.create(story.id(), new TaskRequest("A", "D"));
        taskService.create(story.id(), new TaskRequest("B", "D"));
        taskService.create(story.id(), new TaskRequest("C", "D"));
        UUID autopilotId = engage();
        setMaxParallel(autopilotId, 3);
        autopilotService.tick();
        assertThat(runsFor(autopilotId)).hasSize(3);
        runsFor(autopilotId).forEach(run -> park(run, WorkflowRunStatus.failed));

        autopilotService.tick();

        Autopilot autopilot = autopilotRepo.findById(autopilotId).orElseThrow();
        assertThat(autopilot.getConsecutiveFailures()).isEqualTo(3);
        assertThat(autopilot.isEngaged()).isFalse();
        assertThat(autopilot.getDisengagedReason()).contains("3 consecutive failures");
    }

    /**
     * No mutation may block indefinitely behind an in-flight tick.
     *
     * <p>Deterministic rather than a two-thread race: the test holds the tick's own advisory lock
     * and drives each mutator against it. The lock is held for the whole body, so anything that
     * waits on it waits for the test, which is the worst case a real tick can produce.
     */
    @Test
    void noMutationBlocksIndefinitelyWhileATickHoldsTheLock() throws Exception {
        StoryResponse story = makeStory(makeRepo("autopilot-lock").getId());
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Work", "D"));
        UUID autopilotId = engage();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                lockService.acquireLock(autopilotId);

                // The emergency stop does not wait for the tick at all — it takes no advisory
                // lock, and its row lock is compatible with everything the tick holds.
                assertThat(awaiting(pool.submit(() -> autopilotService.disengage()), 10))
                        .as("Disengage must never wait out a tick")
                        .isNull();

                // engage() does contend, but gives up and says so. A retry is an acceptable
                // answer for turning it on; it would not be for turning it off.
                assertThat(awaiting(pool.submit(() -> autopilotService.engage()), 15))
                        .as("a contended mutation must fail fast, not hang")
                        .isInstanceOf(ConflictException.class);

                // The hazard the tick's javadoc is about, re-checked in this configuration: the
                // lock is advisory, so nothing here holds a row lock on autopilot(id), and a
                // REQUIRES_NEW start — whose foreign key needs FOR KEY SHARE on exactly that row
                // — still completes. Bounded, so a regression fails rather than hangs.
                assertThat(awaiting(pool.submit(() -> taskService.startForAutopilot(task.id(), autopilotId)), 20))
                        .as("a REQUIRES_NEW start must still complete while the advisory lock is held")
                        .isNull();
            });
        } finally {
            pool.shutdownNow();
        }
        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().isEngaged())
                .isFalse();
    }

    /**
     * The lock-free Disengage is still the last word.
     *
     * <p>This is the interleaving that makes dropping the lock safe or unsafe, reproduced exactly:
     * a tick loads the row while it is engaged, a human stops it mid-tick, and the tick then
     * writes its pass back. {@code @DynamicUpdate} means the write-back carries only the columns
     * the tick actually changed, so {@code engaged} is not among them. Without that annotation the
     * full-column UPDATE restores {@code engaged = true} and this test fails — which is how the
     * claim was checked rather than assumed.
     */
    @Test
    void disengage_isNotRevertedByATickWriteBackThatLandsAfterIt() throws Exception {
        UUID autopilotId = engage();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                Autopilot asTheTickSeesIt = autopilotRepo.findById(autopilotId).orElseThrow();
                assertThat(asTheTickSeesIt.isEngaged())
                        .as("the tick's loaded-state snapshot has it ON")
                        .isTrue();
                lockService.acquireLock(autopilotId);

                assertThat(awaiting(pool.submit(() -> autopilotService.disengage()), 10))
                        .isNull();

                // What a tick does at the end of a pass: its own two columns, nothing else.
                asTheTickSeesIt.setConsecutiveFailures(asTheTickSeesIt.getConsecutiveFailures() + 1);
                asTheTickSeesIt.setLastTickAt(Instant.now());
                autopilotRepo.save(asTheTickSeesIt);
            });
        } finally {
            pool.shutdownNow();
        }

        Autopilot after = autopilotRepo.findById(autopilotId).orElseThrow();
        assertThat(after.isEngaged())
                .as("a tick write-back must not turn the Autopilot back on")
                .isFalse();
        assertThat(after.getConsecutiveFailures())
                .as("while the tick's own columns still land")
                .isEqualTo(1);
    }

    /** Runs {@code work}, returning the throwable it produced or null — never blocking past {@code seconds}. */
    private static Throwable awaiting(Future<?> work, int seconds) {
        try {
            work.get(seconds, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            return new AssertionError("did not complete within " + seconds + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * The same property end to end: a real tick and a real Disengage, released together. Whichever
     * order they take, the Autopilot must end up off — the tick either sees the row already
     * disengaged and returns, or runs its pass and leaves {@code engaged} untouched on write-back.
     */
    @Test
    void concurrentTickAndDisengage_theHumanWins() throws Exception {
        StoryResponse story = makeStory(makeRepo("autopilot-stop").getId());
        taskService.create(story.id(), new TaskRequest("Work", "D"));
        UUID autopilotId = engage();

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> outcomes = pool.invokeAll(List.of(
                    released(startLine, () -> autopilotService.tick()),
                    released(startLine, () -> autopilotService.disengage())));
            for (Future<Throwable> outcome : outcomes) {
                assertThat(outcome.get(60, TimeUnit.SECONDS)).isNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().isEngaged())
                .isFalse();

        // The other half of "sensibly": whichever way the race went, the stop is durable. A tick
        // that had already passed its engaged re-check finishes the pass it was on — expected,
        // since Disengage never touches in-flight runs — but no later tick starts anything more.
        int startedByTheRace = runsFor(autopilotId).size();
        autopilotService.tick();
        assertThat(runsFor(autopilotId)).hasSize(startedByTheRace);
    }

    private static Callable<Throwable> released(CyclicBarrier startLine, Runnable action) {
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

    @Test
    void getStatus_withNoRow_insertsNothing() {
        assertThat(autopilotRepo.count()).isZero();

        AutopilotStatusResponse status = autopilotService.getStatus();

        assertThat(status.engaged()).isFalse();
        assertThat(status.maxParallel()).isEqualTo(1);
        assertThat(autopilotRepo.count()).isZero();
    }

    @Test
    void engage_insertsTheRowAndPublishesThroughTheOrgScopedSeam() {
        AutopilotStatusResponse status = autopilotService.engage();
        cleaner.trackAutopilot(autopilotRepo.findAll().getFirst().getId());

        assertThat(status.engaged()).isTrue();
        assertThat(status.lastTickAt()).isNotNull();
        assertThat(autopilotRepo.count()).isEqualTo(1);
        Mockito.verify(runEventPublisher)
                .publishAutopilotChanged(ArgumentMatchers.any(UUID.class), ArgumentMatchers.eq(status));
    }

    // -----------------------------------------------------------------------------------
    // Fixtures — every id is recorded as it is created, so a failure halfway still cleans up
    // -----------------------------------------------------------------------------------

    /** Creates the singleton via the real path and registers it for deletion. */
    private UUID engage() {
        autopilotService.engage();
        Autopilot autopilot = autopilotRepo.findAll().getFirst();
        return cleaner.trackAutopilot(autopilot.getId());
    }

    private void setMaxParallel(UUID autopilotId, int maxParallel) {
        autopilotService.update(maxParallel);
        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().getMaxParallel())
                .isEqualTo(maxParallel);
    }

    /** Moves a run to a status the tick has to react to, without going through Temporal. */
    private void park(WorkflowRun run, WorkflowRunStatus status) {
        WorkflowRun fresh = runRepo.findById(run.getId()).orElseThrow();
        fresh.setStatus(status);
        runRepo.saveAndFlush(fresh);
    }

    private List<WorkflowRun> runsFor(UUID autopilotId) {
        return runRepo.findAll().stream()
                .filter(run -> autopilotId.equals(run.getAutopilotId()))
                .sorted(java.util.Comparator.comparing(WorkflowRun::getCreatedAt))
                .toList();
    }

    private StoryResponse makeStory(UUID softwareProjectId) {
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, softwareProjectId), null);
        cleaner.trackEpic(epic.id());
        return storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
    }

    private GitRepo makeRepo(String slug) {
        // These rows commit, so the URL — unique per repo — carries a nonce.
        String url = "https://github.com/test/" + slug + "-"
                + UUID.randomUUID().toString().substring(0, 8) + ".git";
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        GitRepo saved = gitRepoRepo.save(repo);
        cleaner.trackSoftwareProject(saved.getId());
        return saved;
    }

    @AfterEach
    void removeEverythingThisTestCommitted() {
        cleaner.deleteAll();
    }
}
