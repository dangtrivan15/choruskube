package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.dto.AutopilotStatusResponse;
import com.choruskube.core.dto.AutopilotTaskRef;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.QuotaExceededException;
import com.choruskube.core.model.Autopilot;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The tick's decision-making, isolated from the database. The arithmetic this pins — slot
 * accounting, the failure breaker, settle idempotence, ordering — is where the Autopilot decides
 * to spend money on agent containers, and every branch of it is reachable with mocks. The
 * end-to-end path against real Postgres, real readiness and a real start is pinned separately by
 * {@code AutopilotServiceIntegrationTest}.
 *
 * <p>The repository mocks <strong>emulate the row</strong> rather than record calls: each
 * {@code @Modifying} statement's answer applies that statement's effect to the fixture, so the
 * behavioural assertions below read the same way they did when the tick wrote the row through an
 * entity. That is deliberate — the restructure changed the mechanism, not the arithmetic, and a
 * test suite that had to be rewritten to accommodate it would have hidden exactly that.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutopilotServiceTest {

    @Mock
    private AutopilotRepository autopilotRepo;

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private EpicRepository epicRepo;

    @Mock
    private StoryRepository storyRepo;

    @Mock
    private TaskRepository taskRepo;

    @Mock
    private EpicReadinessAssembler readinessAssembler;

    @Mock
    private AutopilotCandidateSource candidateSource;

    @Mock
    private TaskService taskService;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private PlatformTransactionManager transactionManager;

    private static final String THIS_INSTANCE = "instance-under-test";
    private static final String ANOTHER_INSTANCE = "the-other-replica";

    private final UUID autopilotId = UUID.randomUUID();

    /** The lease columns, emulated so the tests exercise the real acquire/renew/release conditions. */
    private String leaseOwner;

    private Instant leaseUntil;

    private Autopilot autopilot;
    private final List<EpicFixture> epics = new ArrayList<>();
    private final List<WorkflowRun> live = new ArrayList<>();
    private final List<WorkflowRun> settleBatch = new ArrayList<>();
    private final Map<UUID, Readiness> readiness = new HashMap<>();
    private final List<WorkItemDependency> edges = new ArrayList<>();
    private final Map<UUID, Task> tasksById = new LinkedHashMap<>();
    private final Map<UUID, Story> storiesById = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        autopilot = new Autopilot();
        autopilot.setId(autopilotId);
        autopilot.setCreatedAt(Instant.now());
        autopilot.setEngaged(true);
        leaseOwner = null;
        leaseUntil = null;
    }

    // -----------------------------------------------------------------------------------
    // 1 — engagement
    // -----------------------------------------------------------------------------------

    @Test
    void tick_whenDisengaged_doesNothing() {
        autopilot.setEngaged(false);
        Task ready = task(story(epic("E")), "Ready", WorkItemStatus.backlog, Readiness.READY);

        newService().tick();

        verify(autopilotRepo, never()).acquireTickLease(any(), any(), any(), any());
        verify(taskService, never()).startForAutopilot(any(), any());
        assertThat(ready.getStatus()).isEqualTo(WorkItemStatus.backlog);
    }

    @Test
    void tick_whenNoRowExists_doesNothingAndInsertsNothing() {
        autopilot = null;

        newService().tick();

        verify(autopilotRepo, never()).acquireTickLease(any(), any(), any(), any());
        verify(autopilotRepo, never()).insertDefaults(any());
    }

    // -----------------------------------------------------------------------------------
    // 2 — slots
    // -----------------------------------------------------------------------------------

    @Test
    void tick_withOneSlotAndTwoReadyTasks_startsExactlyOne() {
        // The two Tasks sit in Epics of different priority so the comparator, not UUID order,
        // decides which one a single slot buys.
        Task first = task(story(epic("High", Priority.high)), "First", WorkItemStatus.backlog, Readiness.READY);
        task(story(epic("Low", Priority.low)), "Second", WorkItemStatus.backlog, Readiness.READY);

        newService().tick();

        verify(taskService).startForAutopilot(first.getId(), autopilotId);
        verify(taskService, times(1)).startForAutopilot(any(), any());
    }

    @Test
    void tick_whenSlotsFull_startsNothing() {
        StoryFixture s = story(epic("E"));
        task(s, "Ready", WorkItemStatus.backlog, Readiness.READY);
        run(WorkflowRunStatus.running, null);

        newService().tick();

        verify(taskService, never()).startForAutopilot(any(), any());
    }

    @Test
    void tick_parkedRunDoesNotOccupyASlot_soWorkStillStarts() {
        // Decision 2: a run parked on a human dispatches no workload, so holding its slot would
        // idle the Autopilot for exactly as long as the user is away — the original problem.
        StoryFixture s = story(epic("E"));
        Task ready = task(s, "Ready", WorkItemStatus.backlog, Readiness.READY);
        run(WorkflowRunStatus.awaiting_human, null);

        newService().tick();

        verify(taskService).startForAutopilot(ready.getId(), autopilotId);
    }

    @Test
    void tick_awaitingRetryDoesNotOccupyASlot() {
        StoryFixture s = story(epic("E"));
        Task ready = task(s, "Ready", WorkItemStatus.backlog, Readiness.READY);
        WorkflowRun dead = run(WorkflowRunStatus.awaiting_retry, null);
        dead.setAutopilotSettledAt(Instant.now());

        newService().tick();

        verify(taskService).startForAutopilot(ready.getId(), autopilotId);
    }

    @Test
    void tick_blockedTaskIsNotOnTheFrontier() {
        StoryFixture s = story(epic("E"));
        task(s, "Blocked", WorkItemStatus.backlog, Readiness.BLOCKED);
        task(s, "AlreadyStarted", WorkItemStatus.in_progress, Readiness.READY);

        newService().tick();

        verify(taskService, never()).startForAutopilot(any(), any());
    }

    @Test
    void tick_slotTakenByAnotherReplicaMidPass_stopsStarting() {
        // The planning phase counted slots before anything started, and it runs in no transaction
        // of its own — so by the second start another replica's pods may already fill the ceiling.
        // Cheaper to ask again than to hand out capacity that no longer exists.
        Task first = task(story(epic("High", Priority.high)), "First", WorkItemStatus.backlog, Readiness.READY);
        Task second = task(story(epic("Low", Priority.low)), "Second", WorkItemStatus.backlog, Readiness.READY);
        autopilot.setMaxParallel(2);
        when(taskService.startForAutopilot(eq(first.getId()), any())).thenAnswer(invocation -> {
            run(WorkflowRunStatus.running, null);
            run(WorkflowRunStatus.running, null);
            return null;
        });

        newService().tick();

        verify(taskService).startForAutopilot(first.getId(), autopilotId);
        verify(taskService, never()).startForAutopilot(eq(second.getId()), any());
    }

    // -----------------------------------------------------------------------------------
    // 3 — the failure breaker
    // -----------------------------------------------------------------------------------

    @Test
    void tick_quotaExceeded_endsTickWithoutIncrementingFailures() {
        Task first = task(story(epic("High", Priority.high)), "First", WorkItemStatus.backlog, Readiness.READY);
        Task second = task(story(epic("Low", Priority.low)), "Second", WorkItemStatus.backlog, Readiness.READY);
        autopilot.setMaxParallel(2);
        when(taskService.startForAutopilot(eq(first.getId()), any()))
                .thenThrow(new QuotaExceededException("Run quota reached"));

        newService().tick();

        assertThat(autopilot.getConsecutiveFailures()).isZero();
        assertThat(autopilot.isEngaged()).isTrue();
        // Back-pressure ends the whole tick: the next Task would hit the same quota.
        verify(taskService, never()).startForAutopilot(eq(second.getId()), any());
        verify(taskService, times(1)).startForAutopilot(any(), any());
    }

    @Test
    void tick_startThrows_incrementsFailures() {
        StoryFixture s = story(epic("E"));
        Task first = task(s, "First", WorkItemStatus.backlog, Readiness.READY);
        when(taskService.startForAutopilot(eq(first.getId()), any())).thenThrow(new IllegalStateException("boom"));

        newService().tick();

        assertThat(autopilot.getConsecutiveFailures()).isEqualTo(1);
        assertThat(autopilot.isEngaged()).isTrue();
    }

    @Test
    void tick_startThrows_addsToTheCounterRatherThanSettingIt() {
        // The statement is `consecutive_failures = consecutive_failures + 1`, so the caller never
        // supplies the old value and cannot overwrite a concurrent replica's contribution. Pinned
        // here at the call, and against real Postgres by AutopilotServiceIntegrationTest.
        StoryFixture s = story(epic("E"));
        Task first = task(s, "First", WorkItemStatus.backlog, Readiness.READY);
        when(taskService.startForAutopilot(eq(first.getId()), any())).thenThrow(new IllegalStateException("boom"));

        newService().tick();

        verify(autopilotRepo).addFailures(eq(autopilotId), eq(1), any());
    }

    @Test
    void tick_thirdConsecutiveFailure_disengagesWithReason() {
        StoryFixture s = story(epic("E"));
        Task first = task(s, "First", WorkItemStatus.backlog, Readiness.READY);
        autopilot.setConsecutiveFailures(2);
        when(taskService.startForAutopilot(eq(first.getId()), any()))
                .thenThrow(new IllegalStateException("agent image is broken"));

        newService().tick();

        assertThat(autopilot.getConsecutiveFailures()).isEqualTo(3);
        assertThat(autopilot.isEngaged()).isFalse();
        assertThat(autopilot.getDisengagedReason())
                .contains("3 consecutive failures")
                .contains("agent image is broken");
    }

    @Test
    void tick_completedRun_resetsFailures() {
        autopilot.setConsecutiveFailures(2);
        settling(WorkflowRunStatus.completed);

        newService().tick();

        assertThat(autopilot.getConsecutiveFailures()).isZero();
        assertThat(autopilot.isEngaged()).isTrue();
    }

    @Test
    void tick_failedRun_incrementsFailures() {
        settling(WorkflowRunStatus.failed);

        newService().tick();

        assertThat(autopilot.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void tick_awaitingRetryRun_countsAsAFailure() {
        settling(WorkflowRunStatus.awaiting_retry);

        newService().tick();

        assertThat(autopilot.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void tick_cancelledRun_settlesWithoutTouchingTheCounter() {
        // A human cancelling is not the Autopilot failing — and it must not leave stale failure
        // credit behind either, so the run settles with no effect in either direction.
        autopilot.setConsecutiveFailures(1);
        WorkflowRun cancelled = settling(WorkflowRunStatus.cancelled);

        newService().tick();

        assertThat(autopilot.getConsecutiveFailures()).isEqualTo(1);
        assertThat(cancelled.getAutopilotSettledAt()).isNotNull();
        verify(autopilotRepo, never()).addFailures(any(), anyInt(), any());
        verify(autopilotRepo, never()).resetFailures(any(), any());
    }

    @Test
    void tick_mixedSettleBatch_anyFailureWins() {
        // Otherwise the outcome depends on the order the query happened to return rows in.
        autopilot.setConsecutiveFailures(1);
        settling(WorkflowRunStatus.completed);
        settling(WorkflowRunStatus.failed);

        newService().tick();

        assertThat(autopilot.getConsecutiveFailures()).isEqualTo(2);
    }

    @Test
    void tick_settle_stampsEveryRunSoTheNextTickCannotCountItAgain() {
        // awaiting_retry is a durable STATUS, not an event: under a last_tick_at time window, any
        // unrelated re-save of this run would re-enter the window and be counted afresh, and three
        // touches of one dead run would disengage the Autopilot on their own.
        WorkflowRun dead = settling(WorkflowRunStatus.awaiting_retry);

        AutopilotService service = newService();
        service.tick();
        assertThat(dead.getAutopilotSettledAt()).isNotNull();
        assertThat(autopilot.getConsecutiveFailures()).isEqualTo(1);

        // The settle query is keyed on the marker, so a stamped run is simply not in the batch.
        settleBatch.clear();
        service.tick();

        assertThat(autopilot.getConsecutiveFailures()).isEqualTo(1);
        assertThat(autopilot.isEngaged()).isTrue();
    }

    @Test
    void tick_breakerTrips_stopsBeforeStartingAnything() {
        StoryFixture s = story(epic("E"));
        task(s, "Ready", WorkItemStatus.backlog, Readiness.READY);
        autopilot.setConsecutiveFailures(2);
        settling(WorkflowRunStatus.failed);

        newService().tick();

        assertThat(autopilot.isEngaged()).isFalse();
        assertThat(autopilot.getDisengagedReason()).isNotNull();
        verify(taskService, never()).startForAutopilot(any(), any());
    }

    // -----------------------------------------------------------------------------------
    // 4 — the tick lease and the phase boundaries
    // -----------------------------------------------------------------------------------

    @Test
    void tick_claimsTheLeaseAndNeverTakesARowLockOnTheAutopilotRow() {
        // The lease replaced pg_advisory_xact_lock because that lock is TRANSACTION-scoped: once
        // the tick became four short transactions it was released when phase 1 committed, leaving
        // planning and starting open to a second instance counting the same free slots.
        //
        // What must never change is the shape. A start inserts a run whose foreign key takes FOR
        // KEY SHARE on autopilot(id). That is compatible with the FOR NO KEY UPDATE a plain UPDATE
        // takes — including every lease statement — but NOT with FOR UPDATE, and because the tick
        // would not itself be waiting on anything, Postgres would see no cycle and never fire
        // deadlock detection. Every start would block to statement timeout: a hang, not an error.
        story(epic("E"));

        newService().tick();

        verify(autopilotRepo).acquireTickLease(eq(autopilotId), eq(THIS_INSTANCE), any(), any());
        assertThat(Arrays.stream(AutopilotRepository.class.getMethods())
                        .filter(m -> m.isAnnotationPresent(Lock.class))
                        .map(Method::getName))
                .as("a locking finder on the autopilot row is the other way to cause the same hang")
                .isEmpty();
    }

    @Test
    void mutatorsNeverTouchTheLease() {
        // Every mutation is a single statement now, so there is nothing to serialise them against
        // and nothing for them to wait out. This is what retired the lock_timeout and the 409 that
        // used to tell an HTTP caller its change had lost a race with an in-flight tick — an
        // emergency stop must not be refused because a pass happens to be running.
        AutopilotService service = newService();

        service.engage();
        service.update(2);
        service.disengage();

        verify(autopilotRepo, never()).acquireTickLease(any(), any(), any(), any());
        verify(autopilotRepo, never()).renewTickLease(any(), any(), any(), any());
    }

    @Test
    void tick_claimsTheLeaseFirst_rereadsTheRowBeforePublishing_andReleasesLast() {
        // The whole pass, in the only order that makes the phases worth splitting: the lease is
        // claimed before any phase and given back after all of them, and the published payload is
        // built from a read that happens AFTER the starts rather than from the row the pass began
        // with.
        story(epic("E"));
        InOrder inOrder = inOrder(autopilotRepo, eventPublisher);

        newService().tick();

        inOrder.verify(autopilotRepo).acquireTickLease(eq(autopilotId), eq(THIS_INSTANCE), any(), any());
        inOrder.verify(autopilotRepo).stampTick(eq(autopilotId), any());
        inOrder.verify(autopilotRepo).findById(autopilotId);
        inOrder.verify(eventPublisher).publishAutopilotChanged(any(), any());
        inOrder.verify(autopilotRepo).releaseTickLease(eq(autopilotId), eq(THIS_INSTANCE), any());
    }

    @Test
    void tick_whenAnotherInstanceHoldsTheLease_returnsImmediatelyAndDoesNothing() {
        // Skipped, not queued. Waiting would pile instances up behind one slow pass; skipping
        // costs a scheduler interval and the work is still there next time.
        story(epic("E"));
        task(story(epic("Work")), "Ready", WorkItemStatus.backlog, Readiness.READY);
        leaseOwner = ANOTHER_INSTANCE;
        leaseUntil = Instant.now().plus(Duration.ofMinutes(5));

        newService().tick();

        verify(autopilotRepo, never()).stampTick(any(), any());
        verify(taskService, never()).startForAutopilot(any(), any());
        verify(eventPublisher, never()).publishAutopilotChanged(any(), any());
        verify(autopilotRepo, never()).releaseTickLease(any(), eq(ANOTHER_INSTANCE), any());
    }

    @Test
    void tick_whenAnotherInstancesLeaseHasExpired_reclaimsIt() {
        // Self-healing is the reason this is a lease and not a session-scoped lock: an instance
        // that died mid-pass must not wedge the Autopilot until someone restarts something.
        Task ready = task(story(epic("E")), "Ready", WorkItemStatus.backlog, Readiness.READY);
        leaseOwner = ANOTHER_INSTANCE;
        leaseUntil = Instant.now().minus(Duration.ofMinutes(1));

        newService().tick();

        assertThat(leaseOwner).isNull();
        verify(taskService).startForAutopilot(ready.getId(), autopilotId);
    }

    @Test
    void tick_leaseLostMidPass_stopsStartingAndCountsNoFailure() {
        // Overrunning the TTL means another instance is now the one allowed to do this work.
        // Abandoning the pass is the correct response, and it is emphatically not a failure —
        // counting it would let three slow passes disengage the Autopilot.
        Task first = task(story(epic("High", Priority.high)), "First", WorkItemStatus.backlog, Readiness.READY);
        Task second = task(story(epic("Low", Priority.low)), "Second", WorkItemStatus.backlog, Readiness.READY);
        autopilot.setMaxParallel(2);
        when(taskService.startForAutopilot(eq(first.getId()), any())).thenAnswer(invocation -> {
            leaseOwner = ANOTHER_INSTANCE;
            return null;
        });

        newService().tick();

        verify(taskService, never()).startForAutopilot(eq(second.getId()), any());
        assertThat(autopilot.getConsecutiveFailures()).isZero();
        assertThat(autopilot.isEngaged()).isTrue();
        // The instance that took the lease over owns the reporting too — two writers on one live
        // panel would fight, and its view is the current one.
        verify(eventPublisher, never()).publishAutopilotChanged(any(), any());
    }

    @Test
    void tick_releasesTheLeaseEvenWhenAPhaseThrows() {
        // A pass that dies without releasing costs a whole TTL of idleness. Cheap to prevent.
        story(epic("E"));
        // Stubbed after the service is built: newService() re-stubs this mock, and re-stubbing a
        // thenThrow would make the harness itself throw.
        AutopilotService service = newService();
        when(runRepo.findByAutopilotIdAndAutopilotSettledAtIsNullAndStatusIn(any(), any()))
                .thenThrow(new IllegalStateException("database went away"));

        assertThatThrownBy(service::tick).isInstanceOf(IllegalStateException.class);

        verify(autopilotRepo).releaseTickLease(eq(autopilotId), eq(THIS_INSTANCE), any());
    }

    @Test
    void tick_disengagedBetweenTheFirstReadAndTheLease_stopsWithoutStampingOrPublishing() {
        // The read in tick() precedes the lease, so it can already be stale by the time the pass
        // actually begins.
        story(epic("E"));
        // Stubbed after the service is built, so newService()'s own stubbing does not undo it.
        AutopilotService service = newService();
        when(autopilotRepo.findEngagedById(any())).thenReturn(Optional.of(false));

        service.tick();

        verify(autopilotRepo, never()).stampTick(any(), any());
        verify(taskService, never()).startForAutopilot(any(), any());
        verify(eventPublisher, never()).publishAutopilotChanged(any(), any());
        verify(autopilotRepo).releaseTickLease(eq(autopilotId), eq(THIS_INSTANCE), any());
    }

    @Test
    void tick_startRejectedAsAConflict_isNotAFailureAndThePassContinues() {
        // A ConflictException out of startForAutopilot always means the same thing: this Task is
        // no longer the backlog, READY Task the frontier was swept for — a human clicked Start, a
        // dependency appeared, or an instance that overran its lease got here first. The roadmap
        // moved under a plan; the platform is fine. Counting it would disengage the Autopilot
        // after three lost races.
        Task contended = task(story(epic("High", Priority.high)), "Contended", WorkItemStatus.backlog, Readiness.READY);
        Task next = task(story(epic("Low", Priority.low)), "Next", WorkItemStatus.backlog, Readiness.READY);
        autopilot.setMaxParallel(2);
        autopilot.setConsecutiveFailures(2);
        when(taskService.startForAutopilot(eq(contended.getId()), any()))
                .thenThrow(new ConflictException("Can only start tasks in backlog status"));

        newService().tick();

        assertThat(autopilot.getConsecutiveFailures())
                .as("a lost race must never reach the breaker")
                .isEqualTo(2);
        assertThat(autopilot.isEngaged()).isTrue();
        verify(taskService).startForAutopilot(next.getId(), autopilotId);
    }

    // -----------------------------------------------------------------------------------
    // 5 — a stop that lands mid-pass
    // -----------------------------------------------------------------------------------

    @Test
    void tick_disengagedMidPass_startsNothingFurther() {
        // New behaviour, and the reason the emergency stop no longer has to be fast to be
        // effective: previously a pass that had begun ran to the end of its slots whatever a human
        // did, because `engaged` was read once. It is now re-read before every start.
        Task first = task(story(epic("High", Priority.high)), "First", WorkItemStatus.backlog, Readiness.READY);
        Task second = task(story(epic("Low", Priority.low)), "Second", WorkItemStatus.backlog, Readiness.READY);
        autopilot.setMaxParallel(2);
        when(taskService.startForAutopilot(eq(first.getId()), any())).thenAnswer(invocation -> {
            autopilot.setEngaged(false);
            return null;
        });

        newService().tick();

        verify(taskService).startForAutopilot(first.getId(), autopilotId);
        verify(taskService, never()).startForAutopilot(eq(second.getId()), any());
    }

    @Test
    void tick_disengagedMidPass_publishesTheStopRatherThanTheRowThePassBeganWith() {
        // The UI renders STOMP payloads directly, so a stale `engaged: true` here flips the panel
        // back to "Engaged" moments after the user stopped it — while they are watching to confirm
        // the stop worked. Phase 4 re-reads for exactly this.
        Task first = task(story(epic("High", Priority.high)), "First", WorkItemStatus.backlog, Readiness.READY);
        task(story(epic("Low", Priority.low)), "Second", WorkItemStatus.backlog, Readiness.READY);
        autopilot.setMaxParallel(2);
        when(taskService.startForAutopilot(eq(first.getId()), any())).thenAnswer(invocation -> {
            autopilot.setEngaged(false);
            return null;
        });

        AutopilotStatusResponse published = tickAndCapturePublished();

        assertThat(published.engaged())
                .as("the published payload must reflect the row, not the tick's copy of it")
                .isFalse();
        assertThat(published.whyIdle()).containsExactly("Autopilot is not engaged");
    }

    // -----------------------------------------------------------------------------------
    // 6 — ordering
    // -----------------------------------------------------------------------------------

    @Test
    void tick_prefersATaskInAnEpicThatAlreadyHasAnInFlightRun() {
        // Epic affinity (Decision 6), applied as a stable partition of the comparator's output
        // because it depends on what is in flight, which the comparator cannot know.
        EpicFixture high = epic("High", Priority.high);
        EpicFixture low = epic("Low", Priority.low);
        StoryFixture highStory = story(high);
        StoryFixture lowStory = story(low);
        Task highPriority = task(highStory, "High priority", WorkItemStatus.backlog, Readiness.READY);
        Task affine = task(lowStory, "Same Epic as the run in flight", WorkItemStatus.backlog, Readiness.READY);

        Task inFlightTask = task(lowStory, "Already running", WorkItemStatus.in_progress, Readiness.READY);
        run(WorkflowRunStatus.running, inFlightTask.getId());
        autopilot.setMaxParallel(2);

        newService().tick();

        verify(taskService).startForAutopilot(affine.getId(), autopilotId);
        verify(taskService, never()).startForAutopilot(eq(highPriority.getId()), any());
    }

    // -----------------------------------------------------------------------------------
    // 7 — the four reported lists
    // -----------------------------------------------------------------------------------

    @Test
    void getStatus_nextUp_isTheOrderedFrontier() {
        EpicFixture high = epic("High", Priority.high);
        EpicFixture low = epic("Low", Priority.low);
        Task second = task(story(low), "Second", WorkItemStatus.backlog, Readiness.READY);
        Task first = task(story(high), "First", WorkItemStatus.backlog, Readiness.READY);
        task(story(low), "Blocked", WorkItemStatus.backlog, Readiness.BLOCKED);

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.nextUp()).extracting(AutopilotTaskRef::taskId).containsExactly(first.getId(), second.getId());
        assertThat(status.nextUp()).allMatch(ref -> ref.runId() == null);
    }

    @Test
    void tick_nextUp_excludesWhatThisTickJustStarted() {
        Task started = task(story(epic("High", Priority.high)), "First", WorkItemStatus.backlog, Readiness.READY);
        Task queued = task(story(epic("Low", Priority.low)), "Second", WorkItemStatus.backlog, Readiness.READY);

        AutopilotStatusResponse published = tickAndCapturePublished();

        verify(taskService).startForAutopilot(started.getId(), autopilotId);
        // The frontier was swept before any of them moved out of backlog, so excluding by id is
        // what keeps nextUp honest without a second sweep.
        assertThat(published.nextUp()).extracting(AutopilotTaskRef::taskId).containsExactly(queued.getId());
    }

    @Test
    void getStatus_awaitingYou_listsRunsParkedOnAHuman() {
        StoryFixture s = story(epic("E"));
        Task parked = task(s, "Parked", WorkItemStatus.in_progress, Readiness.READY);
        WorkflowRun gate = run(WorkflowRunStatus.awaiting_human, parked.getId());
        WorkflowRun chat = run(WorkflowRunStatus.live_chat, parked.getId());
        run(WorkflowRunStatus.running, parked.getId());

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.awaitingYou())
                .extracting(AutopilotTaskRef::runId)
                .containsExactlyInAnyOrder(gate.getId(), chat.getId());
        assertThat(status.awaitingYou()).allMatch(ref -> "Parked".equals(ref.title()));
        assertThat(status.inFlight()).as("only the running run occupies a slot").isEqualTo(1);
    }

    @Test
    void getStatus_needsAttention_listsRunsHeldForRetry() {
        StoryFixture s = story(epic("E"));
        Task failed = task(s, "Failed", WorkItemStatus.in_progress, Readiness.READY);
        WorkflowRun retry = run(WorkflowRunStatus.awaiting_retry, failed.getId());
        retry.setAutopilotSettledAt(Instant.now());

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.needsAttention())
                .extracting(AutopilotTaskRef::runId, AutopilotTaskRef::status)
                .containsExactly(tuple(retry.getId(), "awaiting_retry"));
        assertThat(status.awaitingYou()).isEmpty();
    }

    @Test
    void getStatus_whyIdle_namesAnEmptyEpicThatIsBlockingWork() {
        // Decision 4: an empty container is never satisfied, so anything it blocks stays blocked
        // forever. The alternative to saying so is silence — the Autopilot simply never picks the
        // work up, and nothing explains why.
        EpicFixture billing = epic("Billing");
        EpicFixture work = epic("Work");
        Task blocked = task(story(work), "Blocked by Billing", WorkItemStatus.backlog, Readiness.BLOCKED);
        blocks(billing.epic.getId(), BlockableItemType.epic, blocked.getId());

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.whyIdle()).contains("Epic 'Billing' — no tasks defined");
    }

    @Test
    void getStatus_whyIdle_ignoresAnEmptyEpicThatBlocksNothing() {
        epic("Half-planned");
        StoryFixture s = story(epic("Work"));
        task(s, "Ready", WorkItemStatus.backlog, Readiness.READY);

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.whyIdle()).noneMatch(reason -> reason.contains("no tasks defined"));
    }

    @Test
    void getStatus_whyIdle_namesARunStuckInPending() {
        // RunService starts the Temporal workflow from an afterCommit hook. If that throws, the
        // row commits as `pending` with nothing left to advance it — and `pending` holds a slot,
        // so at the default maxParallel = 1 the Autopilot idles permanently.
        StoryFixture s = story(epic("E"));
        Task task = task(s, "Stuck", WorkItemStatus.in_progress, Readiness.READY);
        WorkflowRun stuck = run(WorkflowRunStatus.pending, task.getId());
        ReflectionTestUtils.setField(stuck, "createdAt", Instant.now().minus(Duration.ofMinutes(40)));

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.whyIdle())
                .anyMatch(reason -> reason.contains(stuck.getId().toString())
                        && reason.contains("pending")
                        && reason.contains("holding a slot"));
    }

    @Test
    void getStatus_whyIdle_saysNothingIsReadyWhenEveryBacklogTaskIsBlocked() {
        StoryFixture s = story(epic("E"));
        task(s, "Blocked", WorkItemStatus.backlog, Readiness.BLOCKED);

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.whyIdle()).contains("No ready work — all 1 backlog Task(s) are blocked");
    }

    @Test
    void getStatus_whyIdle_saysAtCapacityWhenEverySlotIsInUse() {
        StoryFixture s = story(epic("E"));
        task(s, "Ready", WorkItemStatus.backlog, Readiness.READY);
        run(WorkflowRunStatus.running, null);

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.whyIdle()).contains("At capacity — 1 of 1 slot(s) in use");
        assertThat(status.slots()).isZero();
    }

    @Test
    void tick_whyIdle_reportsQuotaBackPressure() {
        StoryFixture s = story(epic("E"));
        Task first = task(s, "First", WorkItemStatus.backlog, Readiness.READY);
        when(taskService.startForAutopilot(eq(first.getId()), any()))
                .thenThrow(new QuotaExceededException("Run quota reached"));

        AutopilotStatusResponse published = tickAndCapturePublished();

        assertThat(published.whyIdle()).contains("Held back by a quota: Run quota reached");
    }

    @Test
    void getStatus_whenDisengaged_saysSoAndOffersNoFrontier() {
        autopilot.setEngaged(false);
        StoryFixture s = story(epic("E"));
        task(s, "Ready", WorkItemStatus.backlog, Readiness.READY);

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.whyIdle()).containsExactly("Autopilot is not engaged");
        assertThat(status.nextUp()).isEmpty();
        verifyNoInteractions(candidateSource);
    }

    // -----------------------------------------------------------------------------------
    // 8 — read and mutate
    // -----------------------------------------------------------------------------------

    @Test
    void getStatus_withNoRow_returnsSyntheticDisengagedWithoutInserting() {
        autopilot = null;

        AutopilotStatusResponse status = newService().getStatus();

        assertThat(status.engaged()).isFalse();
        assertThat(status.maxParallel()).isEqualTo(1);
        assertThat(status.whyIdle()).containsExactly("Autopilot has never been configured");
        assertThat(status.nextUp()).isEmpty();
        assertThat(status.lastTickAt()).isNull();
        verify(autopilotRepo, never()).insertDefaults(any());
    }

    @Test
    void engage_withNoRow_insertsRowAndClearsFailureState() {
        autopilot = null;

        AutopilotStatusResponse status = newService().engage();

        assertThat(status.engaged()).isTrue();
        assertThat(status.consecutiveFailures()).isZero();
        assertThat(status.disengagedReason()).isNull();
        verify(autopilotRepo).insertDefaults(any());
        verify(eventPublisher).publishAutopilotChanged(any(), eq(status));
    }

    @Test
    void engage_stampsLastTickAt() {
        // Otherwise the panel's "last tick" reads as a time from before the Autopilot was even on.
        autopilot.setEngaged(false);
        autopilot.setConsecutiveFailures(3);
        autopilot.setDisengagedReason("Disengaged after 3 consecutive failures");
        autopilot.setLastTickAt(null);

        AutopilotStatusResponse status = newService().engage();

        assertThat(status.lastTickAt()).isNotNull();
        assertThat(autopilot.getConsecutiveFailures()).isZero();
        assertThat(autopilot.getDisengagedReason()).isNull();
    }

    @Test
    void engage_afterAStop_turnsItBackOn() {
        // No witness, no 409: engage is one statement and waits on nothing, so the later of two
        // clicks is simply the later statement.
        autopilot.setEngaged(false);

        assertThat(newService().engage().engaged()).isTrue();
    }

    @Test
    void disengage_clearsTheFaultBannerAndTouchesNoRuns() {
        autopilot.setDisengagedReason("Disengaged after 3 consecutive failures");
        StoryFixture s = story(epic("E"));
        Task running = task(s, "Running", WorkItemStatus.in_progress, Readiness.READY);
        run(WorkflowRunStatus.running, running.getId());

        AutopilotStatusResponse status = newService().disengage();

        assertThat(status.engaged()).isFalse();
        assertThat(status.disengagedReason())
                .as("a human switching it off is not a fault")
                .isNull();
        assertThat(status.inFlight()).as("work already started stays started").isEqualTo(1);
        verify(taskService, never()).startForAutopilot(any(), any());
    }

    @Test
    void update_setsMaxParallelAndPublishes() {
        AutopilotStatusResponse status = newService().update(4);

        assertThat(status.maxParallel()).isEqualTo(4);
        assertThat(status.slots()).isEqualTo(4);
        verify(eventPublisher).publishAutopilotChanged(eq(autopilotId), eq(status));
    }

    @Test
    void update_withNullMaxParallel_leavesItAlone() {
        autopilot.setMaxParallel(3);

        assertThat(newService().update(null).maxParallel()).isEqualTo(3);
        verify(autopilotRepo, never()).setMaxParallel(any(), anyInt(), any());
    }

    @Test
    void update_belowOne_isRejected() {
        AutopilotService service = newService();

        assertThatThrownBy(() -> service.update(0)).isInstanceOf(BadRequestException.class);
        verify(autopilotRepo, never()).setMaxParallel(any(), anyInt(), any());
    }

    @Test
    void mutatingWhenNoRowExists_insertsThenAppliesTheStatement() {
        // Two statements rather than an entity save: get-or-create, then the change that was
        // actually asked for.
        autopilot = null;

        newService().engage();

        InOrder inOrder = inOrder(autopilotRepo);
        inOrder.verify(autopilotRepo).insertDefaults(any());
        inOrder.verify(autopilotRepo).engage(any(), any());
    }

    // -----------------------------------------------------------------------------------
    // 9 — the regression guard
    // -----------------------------------------------------------------------------------

    @Test
    void nothingCanWriteTheAutopilotRowThroughTheEntity() {
        // The whole design in one assertion. Three rounds of concurrency fixes on this row were
        // the same defect at different offsets — a read-modify-write patched with ordering tools
        // that cannot express "valid only if nobody changed the row since I read it". Every write
        // is now a single statement, and the protection is structural: there is no save on this
        // repository to forget to avoid, and no EntityManager in the service to merge one back.
        //
        // If this fails, someone has re-added an entity write path. Do not delete the assertion —
        // the path is the regression.
        assertThat(Arrays.stream(AutopilotRepository.class.getMethods()).map(Method::getName))
                .as("AutopilotRepository must expose no entity write path — see its javadoc")
                .doesNotContain("save", "saveAll", "saveAndFlush", "saveAllAndFlush", "flush", "delete", "deleteAll");

        assertThat(AutopilotService.class.getDeclaredConstructors()[0].getParameterTypes())
                .as("an EntityManager here would put persist/merge back within reach")
                .doesNotContain(jakarta.persistence.EntityManager.class);
    }

    // -----------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------

    private AutopilotService newService() {
        when(autopilotRepo.findAll()).thenReturn(autopilot == null ? List.of() : List.of(autopilot));
        when(autopilotRepo.findById(any())).thenAnswer(invocation -> Optional.ofNullable(autopilot));
        when(autopilotRepo.findEngagedById(any()))
                .thenAnswer(invocation -> Optional.of(autopilot != null && autopilot.isEngaged()));
        when(autopilotRepo.findMaxParallelById(any()))
                .thenAnswer(
                        invocation -> autopilot == null ? Optional.empty() : Optional.of(autopilot.getMaxParallel()));
        when(autopilotRepo.findConsecutiveFailuresById(any()))
                .thenAnswer(invocation ->
                        autopilot == null ? Optional.empty() : Optional.of(autopilot.getConsecutiveFailures()));
        // The row really does change, so a test can assert on the state the service reads back.
        when(autopilotRepo.insertDefaults(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            if (id == null) {
                return 0;
            }
            autopilot = new Autopilot();
            autopilot.setId(id);
            autopilot.setCreatedAt(Instant.now());
            return 1;
        });
        when(autopilotRepo.engage(any(), any())).thenAnswer(statement(row -> {
            row.setEngaged(true);
            row.setConsecutiveFailures(0);
            row.setDisengagedReason(null);
            row.setLastTickAt(Instant.now());
        }));
        when(autopilotRepo.disengage(any(), any())).thenAnswer(statement(row -> {
            row.setEngaged(false);
            row.setDisengagedReason(null);
        }));
        when(autopilotRepo.disengageWithReason(any(), any(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) == null || autopilot == null) {
                return 0;
            }
            autopilot.setEngaged(false);
            autopilot.setDisengagedReason(invocation.getArgument(1));
            return 1;
        });
        when(autopilotRepo.setMaxParallel(any(), anyInt(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) == null || autopilot == null) {
                return 0;
            }
            autopilot.setMaxParallel(invocation.getArgument(1));
            return 1;
        });
        when(autopilotRepo.addFailures(any(), anyInt(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) == null || autopilot == null) {
                return 0;
            }
            // Note the shape: adds to whatever the row currently holds, never writes back a value
            // the caller read earlier. That is the property AutopilotRepository#addFailures exists
            // for, so emulating it any other way here would let a lost update pass unnoticed.
            autopilot.setConsecutiveFailures(autopilot.getConsecutiveFailures() + (int) invocation.getArgument(1));
            return 1;
        });
        when(autopilotRepo.resetFailures(any(), any())).thenAnswer(statement(row -> row.setConsecutiveFailures(0)));
        when(autopilotRepo.stampTick(any(), any())).thenAnswer(statement(row -> row.setLastTickAt(Instant.now())));
        // The lease conditions, emulated rather than stubbed to a constant: whether a pass may run
        // is the property under test in half a dozen cases below, and a mock that always says yes
        // would make all of them pass for the wrong reason.
        when(autopilotRepo.acquireTickLease(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) == null) {
                return 0;
            }
            Instant now = invocation.getArgument(3);
            if (leaseUntil != null && leaseUntil.isAfter(now)) {
                return 0;
            }
            leaseOwner = invocation.getArgument(1);
            leaseUntil = invocation.getArgument(2);
            return 1;
        });
        when(autopilotRepo.renewTickLease(any(), any(), any(), any())).thenAnswer(invocation -> {
            Instant now = invocation.getArgument(3);
            if (!java.util.Objects.equals(leaseOwner, invocation.getArgument(1))
                    || leaseUntil == null
                    || leaseUntil.isBefore(now)) {
                return 0;
            }
            leaseUntil = invocation.getArgument(2);
            return 1;
        });
        when(autopilotRepo.releaseTickLease(any(), any(), any())).thenAnswer(invocation -> {
            if (!java.util.Objects.equals(leaseOwner, invocation.getArgument(1))) {
                return 0;
            }
            leaseOwner = null;
            leaseUntil = null;
            return 1;
        });
        // The null guards are not defensive padding: re-stubbing calls the mock with null
        // arguments, so a test that builds the service twice would otherwise run the answer
        // installed by the first build — and these answers MUTATE the fixture.
        when(runRepo.findByAutopilotIdAndStatusIn(any(), any())).thenAnswer(invocation -> {
            Set<?> statuses = statusesOf(invocation.getArgument(1));
            return live.stream().filter(r -> statuses.contains(r.getStatus())).toList();
        });
        when(runRepo.countByAutopilotIdAndStatusIn(any(), any())).thenAnswer(invocation -> {
            Set<?> statuses = statusesOf(invocation.getArgument(1));
            return live.stream().filter(r -> statuses.contains(r.getStatus())).count();
        });
        when(runRepo.findByAutopilotIdAndAutopilotSettledAtIsNullAndStatusIn(any(), any()))
                .thenAnswer(invocation -> {
                    Set<?> statuses = statusesOf(invocation.getArgument(1));
                    return settleBatch.stream()
                            .filter(r -> r.getAutopilotSettledAt() == null)
                            .filter(r -> statuses.contains(r.getStatus()))
                            .toList();
                });
        when(candidateSource.candidateEpicIds(any()))
                .thenReturn(epics.stream().map(e -> e.epic.getId()).toList());
        when(taskRepo.findAllById(any())).thenAnswer(invocation -> {
            Iterable<?> ids = invocation.getArgument(0);
            List<Task> found = new ArrayList<>();
            if (ids == null) {
                return found;
            }
            ids.forEach(id -> {
                Task task = tasksById.get(id);
                if (task != null) {
                    found.add(task);
                }
            });
            return found;
        });
        when(taskRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(tasksById.get(inv.getArgument(0))));
        when(storyRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(storiesById.get(inv.getArgument(0))));
        for (EpicFixture fixture : epics) {
            when(epicRepo.findById(fixture.epic.getId())).thenReturn(Optional.of(fixture.epic));
            when(readinessAssembler.loadEpicCandidates(fixture.epic.getId())).thenReturn(fixture.candidates());
            when(readinessAssembler.assemble(
                            argThat(ids -> ids != null && ids.contains(fixture.epic.getId())),
                            any(),
                            any(),
                            eq(ReadinessAuthMode.AUTOPILOT),
                            any()))
                    // The readiness map and the edge list are shared across Epics on purpose: the
                    // service merges them anyway, and a superset changes no assertion here.
                    .thenReturn(new EpicReadinessAssembler.Assembly(readiness, List.of(), List.of(), edges));
        }
        return new AutopilotService(
                autopilotRepo,
                runRepo,
                epicRepo,
                storyRepo,
                taskRepo,
                readinessAssembler,
                candidateSource,
                taskService,
                eventPublisher,
                transactionManager,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                THIS_INSTANCE);
    }

    /** One targeted UPDATE, applied to the fixture — and a no-op for the null call re-stubbing makes. */
    private Answer<Integer> statement(Consumer<Autopilot> change) {
        return invocation -> {
            if (invocation.getArgument(0) == null || autopilot == null) {
                return 0;
            }
            change.accept(autopilot);
            return 1;
        };
    }

    private static Set<?> statusesOf(Object argument) {
        return argument == null ? Set.of() : new HashSet<>((java.util.Collection<?>) argument);
    }

    /** Runs a tick and returns the status it broadcast — the tick's own view of what it did. */
    private AutopilotStatusResponse tickAndCapturePublished() {
        newService().tick();
        org.mockito.ArgumentCaptor<AutopilotStatusResponse> captor =
                org.mockito.ArgumentCaptor.forClass(AutopilotStatusResponse.class);
        verify(eventPublisher).publishAutopilotChanged(eq(autopilotId), captor.capture());
        return captor.getValue();
    }

    private record EpicFixture(Epic epic, List<Story> stories, Map<UUID, List<Task>> tasksByStoryId) {

        EpicReadinessAssembler.EpicCandidates candidates() {
            Set<UUID> candidateIds = new HashSet<>();
            candidateIds.add(epic.getId());
            stories.forEach(s -> {
                candidateIds.add(s.getId());
                tasksByStoryId.getOrDefault(s.getId(), List.of()).forEach(t -> candidateIds.add(t.getId()));
            });
            // statusById/parentOf are the assembler's own inputs and are stubbed out with it, so
            // they are handed back empty rather than reconstructed.
            return new EpicReadinessAssembler.EpicCandidates(stories, tasksByStoryId, candidateIds, Map.of(), Map.of());
        }
    }

    private record StoryFixture(Story story, EpicFixture epic) {}

    private EpicFixture epic(String title) {
        return epic(title, Priority.medium);
    }

    private EpicFixture epic(String title, Priority priority) {
        Epic epic = new Epic();
        epic.setId(UUID.randomUUID());
        epic.setTitle(title);
        epic.setPriority(priority);
        epic.setStage(WorkItemStatus.backlog);
        EpicFixture fixture = new EpicFixture(epic, new ArrayList<>(), new LinkedHashMap<>());
        epics.add(fixture);
        return fixture;
    }

    private StoryFixture story(EpicFixture epic) {
        Story story = new Story();
        story.setId(UUID.randomUUID());
        story.setEpicId(epic.epic.getId());
        story.setTitle("Story");
        story.setPriority(Priority.medium);
        story.setStage(WorkItemStatus.backlog);
        epic.stories.add(story);
        epic.tasksByStoryId.put(story.getId(), new ArrayList<>());
        storiesById.put(story.getId(), story);
        return new StoryFixture(story, epic);
    }

    private Task task(StoryFixture story, String title, WorkItemStatus status, Readiness readinessValue) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setStoryId(story.story.getId());
        task.setTitle(title);
        task.setStatus(status);
        story.epic.tasksByStoryId.get(story.story.getId()).add(task);
        tasksById.put(task.getId(), task);
        readiness.put(task.getId(), readinessValue);
        return task;
    }

    /** A live run belonging to this Autopilot. */
    private WorkflowRun run(WorkflowRunStatus status, UUID taskId) {
        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID());
        run.setStatus(status);
        run.setTaskId(taskId);
        run.setAutopilotId(autopilotId);
        ReflectionTestUtils.setField(run, "createdAt", Instant.now());
        live.add(run);
        return run;
    }

    /** A run waiting to be counted by the breaker. */
    private WorkflowRun settling(WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID());
        run.setStatus(status);
        run.setAutopilotId(autopilotId);
        ReflectionTestUtils.setField(run, "createdAt", Instant.now());
        settleBatch.add(run);
        if (status == WorkflowRunStatus.awaiting_retry) {
            live.add(run);
        }
        return run;
    }

    private void blocks(UUID blockingId, BlockableItemType blockingType, UUID blockedId) {
        WorkItemDependency edge = new WorkItemDependency();
        edge.setId(UUID.randomUUID());
        edge.setBlockingItemType(blockingType);
        edge.setBlockingItemId(blockingId);
        edge.setBlockedItemType(BlockableItemType.task);
        edge.setBlockedItemId(blockedId);
        edges.add(edge);
    }
}
