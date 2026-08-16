package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The tick's decision-making, isolated from the database. The arithmetic this pins — slot
 * accounting, the failure breaker, settle idempotence, ordering — is where the Autopilot decides
 * to spend money on agent containers, and every branch of it is reachable with mocks. The
 * end-to-end path against real Postgres, real readiness and a real {@code REQUIRES_NEW} start is
 * pinned separately by {@code AutopilotServiceIntegrationTest}.
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
    private LockService lockService;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private EntityManager entityManager;

    private final UUID autopilotId = UUID.randomUUID();

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
    }

    // -----------------------------------------------------------------------------------
    // 1 — engagement
    // -----------------------------------------------------------------------------------

    @Test
    void tick_whenDisengaged_doesNothing() {
        autopilot.setEngaged(false);
        Task ready = task(story(epic("E")), "Ready", WorkItemStatus.backlog, Readiness.READY);

        newService().tick();

        verifyNoInteractions(lockService);
        verify(taskService, never()).startForAutopilot(any(), any());
        assertThat(ready.getStatus()).isEqualTo(WorkItemStatus.backlog);
    }

    @Test
    void tick_whenNoRowExists_doesNothingAndInsertsNothing() {
        autopilot = null;

        newService().tick();

        verifyNoInteractions(lockService);
        verify(autopilotRepo, never()).save(any());
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
    // 4 — locking
    // -----------------------------------------------------------------------------------

    @Test
    void tick_takesTheAdvisoryLockAndNeverARowLockOnTheAutopilotRow() {
        // startForAutopilot runs REQUIRES_NEW, on a second connection and therefore a separate
        // Postgres session; its insert takes FOR KEY SHARE on autopilot(id) for the foreign key.
        // That is compatible with the FOR NO KEY UPDATE a plain UPDATE takes, but NOT with FOR
        // UPDATE — and because the tick would not itself be waiting on anything, Postgres would
        // see no cycle and never fire deadlock detection. Every start would block to statement
        // timeout, presenting as a hang rather than an error.
        story(epic("E"));

        newService().tick();

        verify(lockService).acquireLock(autopilotId);
        verify(entityManager, never()).lock(any(), any(LockModeType.class));
        assertThat(Arrays.stream(AutopilotRepository.class.getMethods())
                        .filter(m -> m.isAnnotationPresent(Lock.class))
                        .map(Method::getName))
                .as("a locking finder on the autopilot row is the other way to cause the same hang")
                .isEmpty();
    }

    @Test
    void tick_rereadsTheRowAfterTakingTheLock() {
        // The row is read BEFORE the lock, so on a second replica it may already be another tick's
        // stale "before" image; saving that back would silently undo that tick's failure count.
        story(epic("E"));

        newService().tick();

        verify(entityManager).refresh(autopilot);
    }

    // -----------------------------------------------------------------------------------
    // 5 — ordering
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
    // 6 — the four reported lists
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
        // The start committed on another connection, so this persistence context still holds the
        // Task at its pre-start `backlog` — excluding it by id is what keeps nextUp honest.
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
    // 7 — read and mutate
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
        verify(autopilotRepo, never()).save(any());
    }

    @Test
    void engage_withNoRow_insertsRowAndClearsFailureState() {
        autopilot = null;

        AutopilotStatusResponse status = newService().engage();

        assertThat(status.engaged()).isTrue();
        assertThat(status.consecutiveFailures()).isZero();
        assertThat(status.disengagedReason()).isNull();
        verify(autopilotRepo).save(any(Autopilot.class));
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
    }

    @Test
    void update_belowOne_isRejected() {
        AutopilotService service = newService();

        assertThatThrownBy(() -> service.update(0)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void engageAndUpdateSerialiseAgainstATickOnTheSameAdvisoryLock() {
        // The advisory lock used to cover tick-against-tick only. A mutation could then land in
        // the middle of a tick — a window spanning a full readiness sweep and every
        // startForAutopilot call — and the tick's later write-back would restore the counters it
        // shares with them.
        newService().engage();
        newService().update(2);

        verify(lockService, times(2)).acquireLock(autopilotId);
        verify(entityManager, times(2)).refresh(autopilot);
    }

    @Test
    void disengage_takesNoLockAtAll() {
        // The emergency stop must not wait out an in-flight tick. It is safe without the lock
        // because the tick's write-back omits `engaged` unless the breaker set it — and the
        // breaker sets it to false too, so no interleaving turns the Autopilot back ON.
        newService().disengage();

        verifyNoInteractions(lockService);
        verify(autopilotRepo).disengage(autopilotId);
        verify(autopilotRepo, never()).save(any());
    }

    @Test
    void engage_boundsItsWaitAndReportsAConflictRatherThanHanging() {
        // A tick can hold the lock for a full sweep plus every container start, so an untimed
        // wait would leave the HTTP request hanging for as long as the tick runs.
        AutopilotService service = newService();
        doThrow(new CannotAcquireLockException("lock timeout"))
                .when(lockService)
                .acquireLock(any());

        assertThatThrownBy(service::engage)
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("tick is in progress");
        verify(entityManager).createNativeQuery(argThat(sql -> sql.startsWith("SET LOCAL lock_timeout")));
    }

    @Test
    void mutatingWhenNoRowExists_takesNoLock() {
        // Nothing to serialise against: no row means no tick can be running on it.
        autopilot = null;

        newService().engage();

        verifyNoInteractions(lockService);
    }

    @Test
    void mutatorRereadsTheRowAfterTakingTheLock() {
        // Symmetric to the tick's own refresh: this read happened before the lock, so a mutation
        // queued behind a tick would otherwise write back the counters that tick just settled.
        InOrder inOrder = inOrder(lockService, entityManager);

        newService().engage();

        inOrder.verify(lockService).acquireLock(autopilotId);
        inOrder.verify(entityManager).refresh(autopilot);
    }

    // -----------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------

    private AutopilotService newService() {
        // The mutators bound their lock wait with a SET LOCAL statement before acquiring.
        when(entityManager.createNativeQuery(anyString())).thenReturn(mock(jakarta.persistence.Query.class));
        when(autopilotRepo.findAll()).thenReturn(autopilot == null ? List.of() : List.of(autopilot));
        when(autopilotRepo.disengage(any())).thenAnswer(invocation -> {
            // Stands in for the UPDATE statement plus the refresh that follows it: the row really
            // does change, so a test can assert on the status the service builds afterwards.
            if (autopilot == null) {
                return 0;
            }
            autopilot.setEngaged(false);
            autopilot.setDisengagedReason(null);
            return 1;
        });
        when(autopilotRepo.save(any(Autopilot.class))).thenAnswer(invocation -> {
            Autopilot saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                // Stands in for the identifier generator, so a first-write path has an id to
                // publish and to query runs by.
                saved.setId(UUID.randomUUID());
                saved.setCreatedAt(Instant.now());
            }
            return saved;
        });
        // The null guards are not defensive padding: re-stubbing calls the mock with null
        // arguments, so a test that builds the service twice would otherwise NPE inside the
        // answer installed by the first build.
        when(runRepo.findByAutopilotIdAndStatusIn(any(), any())).thenAnswer(invocation -> {
            Set<?> statuses = statusesOf(invocation.getArgument(1));
            return live.stream().filter(r -> statuses.contains(r.getStatus())).toList();
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
                lockService,
                eventPublisher,
                entityManager,
                Duration.ofMinutes(15));
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
