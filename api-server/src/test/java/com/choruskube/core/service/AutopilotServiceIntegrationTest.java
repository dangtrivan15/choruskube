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
import com.choruskube.core.event.MappableCreated;
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
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The tick against real Postgres, real readiness and a real start.
 *
 * <p>Deliberately NOT {@code @Transactional}. Every phase of the tick opens its own transaction, so
 * none of them can see rows a roll-back-only test transaction has written but not committed — the
 * fixtures have to be committed for the tick to find them at all. What that buys, beyond the unit
 * suite: the {@code V15} settle column and {@code V16}'s lease columns exist and round-trip,
 * {@code workflow_run.autopilot_id}'s foreign key is satisfied by a real row, the failure counter's
 * in-database increment survives two concurrent writers, and the lease statements genuinely
 * exclude a second instance rather than merely appearing to — none of which a mock can show.
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
    private AutopilotResolver autopilotResolver;

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
    private JdbcTemplate jdbc;

    private CommittedFixtureCleaner cleaner;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    /**
     * Registered per test rather than declared as a {@code @TestConfiguration} bean, and that
     * distinction is the whole reason this observation is affordable. A {@code @TestConfiguration}
     * changes the class's context cache key: this class would stop sharing its cached context with
     * its {@code @MockitoBean} siblings, get one of its own, and bring one more Hikari pool to the
     * single Postgres container the suite shares — which exhausts {@code max_connections} and fails
     * an unrelated test class with a context-load error. Autowiring the context and adding the
     * listener by hand changes no key at all.
     *
     * <p>Removed in {@code @AfterEach} for the same reason it is added here: the context is shared,
     * so a listener left behind would follow every class that reuses it.
     */
    @Autowired
    private ConfigurableApplicationContext applicationContext;

    /**
     * Synchronized because the publishing thread is whichever thread called the mutator, and this
     * class runs mutators on a pool in several places. No test races them onto the creating call
     * today; a plain {@code ArrayList} would make the first one that does fail intermittently and
     * for a reason nobody would look for here.
     */
    private final List<MappableCreated> ownershipEvents = Collections.synchronizedList(new ArrayList<>());

    private final List<Boolean> aTransactionWasActiveAtPublish = Collections.synchronizedList(new ArrayList<>());

    /**
     * Typed on {@code ApplicationEvent} and narrowed by hand, because {@link MappableCreated} is a
     * plain record rather than an {@code ApplicationEvent}: Spring wraps it in a {@code
     * PayloadApplicationEvent} on the way out, and that wrapper is what a listener registered
     * programmatically actually sees.
     */
    private final ApplicationListener<ApplicationEvent> ownershipEventRecorder = event -> {
        if (event instanceof PayloadApplicationEvent<?> payload
                && payload.getPayload() instanceof MappableCreated created) {
            ownershipEvents.add(created);
            aTransactionWasActiveAtPublish.add(TransactionSynchronizationManager.isActualTransactionActive());
        }
    };

    @BeforeEach
    void setUp() {
        applicationContext.addApplicationListener(ownershipEventRecorder);
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
        // The whole point of the feature: a run waiting on a human dispatches no
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
     * No mutation may block behind an in-flight pass — not even for a bounded wait.
     *
     * <p>Deterministic rather than a two-thread race: the test claims the tick lease on behalf of
     * another instance and drives each mutator against it. Nothing a pass holds is a row lock on
     * the autopilot row, so a mutation contends with at most one statement.
     *
     * <p>This used to assert that {@code engage()} came back with a {@code ConflictException}
     * after a bounded wait on the advisory lock. That machinery is gone: every mutation is a
     * single statement, so there is nothing to wait for and nothing to refuse.
     */
    @Test
    void noMutationWaitsForAnInFlightPass() throws Exception {
        StoryResponse story = makeStory(makeRepo("autopilot-lease").getId());
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Work", "D"));
        UUID autopilotId = engage();
        assertThat(autopilotRepo.acquireTickLease(autopilotId, "another-instance", 120))
                .as("stand in for a pass that is running right now, on another instance")
                .isEqualTo(1);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            assertThat(awaiting(pool.submit(() -> autopilotService.disengage()), 10))
                    .as("Disengage must never wait out a pass")
                    .isNull();
            assertThat(awaiting(pool.submit(() -> autopilotService.engage()), 10))
                    .as("nor may engage — it no longer contends with anything")
                    .isNull();
            assertThat(awaiting(pool.submit(() -> autopilotService.update(2)), 10))
                    .isNull();

            // The hazard the tick's javadoc is about, re-checked in this configuration: nothing
            // holds a row lock on autopilot(id), so a start — whose foreign key needs FOR KEY
            // SHARE on exactly that row — still completes. Bounded, so a regression fails rather
            // than hangs.
            assertThat(awaiting(pool.submit(() -> taskService.startForAutopilot(task.id(), autopilotId)), 20))
                    .as("a start must still complete while a pass holds the lease")
                    .isNull();
        } finally {
            pool.shutdownNow();
        }
        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().getMaxParallel())
                .isEqualTo(2);
    }

    /**
     * A pass may write its own columns without ever restoring one a human just changed.
     *
     * <p>The interleaving is the one that produced the original defect: a pass reads the row while
     * it is engaged, a human stops it mid-pass, and the pass then records what it did. It used to
     * be survivable only because {@code @DynamicUpdate} narrowed the tick's entity write-back to
     * the columns it had touched — one annotation away from turning the Autopilot back on under a
     * user watching the emergency stop succeed.
     *
     * <p>Nothing is written back now. {@code stampTick} and {@code addFailures} name their columns
     * in the statement, so {@code engaged} is not merely absent from the UPDATE by good fortune —
     * there is no expression in which it could appear.
     */
    @Test
    void aPassRecordingItsWorkAfterADisengage_doesNotTurnTheAutopilotBackOn() {
        UUID autopilotId = engage();
        Autopilot asThePassSawIt = autopilotRepo.findById(autopilotId).orElseThrow();
        assertThat(asThePassSawIt.isEngaged()).as("the pass began with it ON").isTrue();

        autopilotService.disengage();

        // What a pass does after its starts: its own columns, by name, from statements that never
        // supply a value they read earlier.
        autopilotRepo.stampTick(autopilotId, Instant.now());
        autopilotRepo.addFailures(autopilotId, 1, Instant.now());

        Autopilot after = autopilotRepo.findById(autopilotId).orElseThrow();
        assertThat(after.isEngaged())
                .as("a pass must not turn the Autopilot back on")
                .isFalse();
        assertThat(after.getConsecutiveFailures())
                .as("while the pass's own columns still land")
                .isEqualTo(1);
        assertThat(after.getLastTickAt()).isNotNull();
    }

    /**
     * Two passes cannot run at once, and the loser does not wait.
     *
     * <p>This is what the tick lease exists for. The advisory lock it replaced was
     * transaction-scoped, so once the tick became four short transactions it was released the
     * moment the settle phase committed — and two instances would each go on to count the same
     * free slots and start the same work, breaking {@code max_parallel}.
     */
    @Test
    void aSecondInstanceSkipsThePassRatherThanRunningItToo() {
        StoryResponse story = makeStory(makeRepo("autopilot-second-instance").getId());
        taskService.create(story.id(), new TaskRequest("Work", "D"));
        UUID autopilotId = engage();
        autopilotRepo.acquireTickLease(autopilotId, "another-instance", 120);

        autopilotService.tick();

        assertThat(runsFor(autopilotId))
                .as("the pass belongs to the instance holding the lease")
                .isEmpty();
        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().getTickOwner())
                .as("and the loser must not have stolen or released it")
                .isEqualTo("another-instance");
    }

    /** An instance that died mid-pass must not wedge the Autopilot past the lease TTL. */
    @Test
    void anExpiredLeaseIsReclaimedByTheNextPass() {
        StoryResponse story = makeStory(makeRepo("autopilot-stale-lease").getId());
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Work", "D"));
        UUID autopilotId = engage();
        // A zero-second TTL is expired the moment the database's clock moves on.
        autopilotRepo.acquireTickLease(autopilotId, "instance-that-died", 0);

        autopilotService.tick();

        assertThat(runsFor(autopilotId)).hasSize(1);
        assertThat(runsFor(autopilotId).getFirst().getTaskId()).isEqualTo(task.id());
        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().getTickOwner())
                .as("released at the end of the pass, so the next interval need not wait out a TTL")
                .isNull();
    }

    /**
     * The failure counter under two concurrent writers.
     *
     * <p>{@code consecutive_failures = consecutive_failures + 1} in the statement, so the answer
     * is 2. The read-increment-write this replaced would produce 1 whenever the two overlapped,
     * and one real failure would vanish — which is how a broken platform kept the Autopilot
     * engaged.
     */
    @Test
    void concurrentFailureIncrements_bothCount() throws Exception {
        UUID autopilotId = engage();
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (Future<Throwable> outcome : pool.invokeAll(List.of(
                    released(startLine, () -> autopilotRepo.addFailures(autopilotId, 1, Instant.now())),
                    released(startLine, () -> autopilotRepo.addFailures(autopilotId, 1, Instant.now()))))) {
                assertThat(outcome.get(30, TimeUnit.SECONDS)).isNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(autopilotRepo.findConsecutiveFailuresById(autopilotId))
                .as("two increments, two failures — a lost update would leave 1")
                .contains(2);
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
     * disengaged and returns, or runs its pass writing only the columns it names.
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

    /**
     * The later of two clicks wins, in both directions.
     *
     * <p>There used to be a hole here, and a pre-wait witness in {@code engage()} guarding it: an
     * {@code engage()} could sit queued on the advisory lock while a lock-free stop jumped ahead
     * of it, and then commit on top — the Autopilot back ON because of a click that came earlier.
     *
     * <p>Neither mutator queues on anything now. Each is a single statement, so ordering is the
     * order the statements arrive in, which for a user is the order they clicked. The witness and
     * the 409 it threw are gone with the wait that made them necessary; this is what remains to
     * assert, and it holds by construction rather than by arbitration.
     */
    @Test
    void theLaterClickWins() {
        UUID autopilotId = engage();

        autopilotService.disengage();
        autopilotService.engage();
        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().isEngaged())
                .isTrue();

        autopilotService.engage();
        autopilotService.disengage();
        assertThat(autopilotRepo.findById(autopilotId).orElseThrow().isEngaged())
                .isFalse();
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

    /**
     * A transaction is active when the ownership event is published.
     *
     * <p>Observable here specifically because this class is NOT {@code @Transactional}: it opens
     * none of its own, so the only transaction that can be active when the listener runs is one
     * {@code engage()} opened. In a {@code @Transactional} test the assertion would pass for free
     * and prove nothing.
     *
     * <p><strong>What this does and does not prove.</strong> It catches the publish moving to an
     * {@code afterCommit} hook, and a mutator losing its {@code @Transactional} — both of which
     * leave no transaction active here. It does <em>not</em> prove the publish shares the
     * <em>insert's</em> transaction: were {@code insertDefaults} to become {@code REQUIRES_NEW}, the
     * outer transaction would still be active while the insert had already committed on its own,
     * and this would still pass. That case is covered by {@code
     * AutopilotServiceTest#everyPublicMethodExceptTickJoinsItsCallersTransaction}, which asserts
     * propagation rather than presence. Named for what it observes so the two are not mistaken for
     * one.
     */
    @Test
    void creatingTheRow_publishesTheOwnershipEventWhileATransactionIsActive() {
        UUID autopilotId = engage();

        assertThat(ownershipEvents)
                .as("one insert, one ownership event, naming the row and the type the writer switches on")
                .containsExactly(MappableCreated.of("autopilot", autopilotId));
        assertThat(aTransactionWasActiveAtPublish)
                .as("a synchronous listener with a transaction still open, not a post-commit hook")
                .containsExactly(true);
    }

    /**
     * What the scheduler passes over, against the real table.
     *
     * <p>The tick loops over exactly this list, so an id that is missing here is an Autopilot that
     * silently never runs — the failure mode the seam exists to prevent, and one no assertion on
     * {@code tick()} alone would name.
     */
    @Test
    void findAllEngaged_followsTheRowsEngagement() {
        assertThat(autopilotResolver.findAllEngaged())
                .as("no row at all is not an Autopilot to pass over")
                .isEmpty();

        UUID autopilotId = engage();
        assertThat(autopilotResolver.findAllEngaged()).containsExactly(autopilotId);

        autopilotService.disengage();
        assertThat(autopilotResolver.findAllEngaged())
                .as("a human said no; the scheduler must not claim a lease on it")
                .isEmpty();
    }

    @Test
    void findAllEngaged_neverCreatesTheRow() {
        // It runs on a timer thread, where the ownership event an insert has to publish cannot be
        // resolved. An installation that never engaged the Autopilot must stay that way.
        assertThat(autopilotResolver.findAllEngaged()).isEmpty();

        assertThat(autopilotRepo.count()).isZero();
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
        applicationContext.removeApplicationListener(ownershipEventRecorder);
        cleaner.deleteAll();
    }
}
