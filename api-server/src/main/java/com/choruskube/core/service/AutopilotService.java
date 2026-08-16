package com.choruskube.core.service;

import com.choruskube.core.dto.AutopilotStatusResponse;
import com.choruskube.core.dto.AutopilotTaskRef;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.QuotaExceededException;
import com.choruskube.core.model.Autopilot;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Autopilot: a standing controller that starts READY Tasks unattended (Decision 1). One row
 * per installation (Decision 7), no terminal state — an empty ready frontier means idle, not
 * finished.
 *
 * <p>Lives in this package because {@link EpicReadinessAssembler} and both of its methods are
 * package-private, and the Autopilot has to compute readiness exactly the way the board does.
 *
 * <p><strong>Nothing here may read request-scoped state.</strong> The tick runs on a timer thread:
 * no {@code ScopeProvider}, no tenant context. Scope comes from the Autopilot's own id, through
 * {@link AutopilotCandidateSource}, and authorization from {@link ReadinessAuthMode#AUTOPILOT}.
 */
@Service
public class AutopilotService {

    private static final Logger log = LoggerFactory.getLogger(AutopilotService.class);

    /** Decision 5: three consecutive failures mean the platform is broken, not the work. */
    private static final int FAILURE_LIMIT = 3;

    /** {@code nextUp} is a preview panel, not a queue dump. */
    private static final int NEXT_UP_LIMIT = 10;

    private final AutopilotRepository autopilotRepo;
    private final WorkflowRunRepository runRepo;
    private final EpicRepository epicRepo;
    private final StoryRepository storyRepo;
    private final TaskRepository taskRepo;
    private final EpicReadinessAssembler readinessAssembler;
    private final AutopilotCandidateSource candidateSource;
    private final TaskService taskService;
    private final LockService lockService;
    private final RunEventPublisher eventPublisher;
    private final EntityManager entityManager;
    private final Duration stalePendingAfter;

    public AutopilotService(
            AutopilotRepository autopilotRepo,
            WorkflowRunRepository runRepo,
            EpicRepository epicRepo,
            StoryRepository storyRepo,
            TaskRepository taskRepo,
            EpicReadinessAssembler readinessAssembler,
            AutopilotCandidateSource candidateSource,
            TaskService taskService,
            LockService lockService,
            RunEventPublisher eventPublisher,
            EntityManager entityManager,
            @Value("${choruskube.autopilot.stale-pending-after:PT15M}") Duration stalePendingAfter) {
        this.autopilotRepo = autopilotRepo;
        this.runRepo = runRepo;
        this.epicRepo = epicRepo;
        this.storyRepo = storyRepo;
        this.taskRepo = taskRepo;
        this.readinessAssembler = readinessAssembler;
        this.candidateSource = candidateSource;
        this.taskService = taskService;
        this.lockService = lockService;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
        this.stalePendingAfter = stalePendingAfter;
    }

    // ---------------------------------------------------------------------------------------
    // Run classification — the single place a WorkflowRunStatus means anything to the Autopilot
    // ---------------------------------------------------------------------------------------

    /** Whether a finished run counts toward, or clears, the failure breaker. */
    private enum Settle {
        NOT_FINISHED,
        SUCCESS,
        FAILURE,
        /** Settled, but neither: a human cancelling a run is not the Autopilot failing. */
        NEUTRAL
    }

    /** Which reported list, if any, a live run belongs in. */
    private enum Bucket {
        NONE,
        AWAITING_YOU,
        NEEDS_ATTENTION
    }

    private record RunClass(boolean occupiesSlot, Settle settle, Bucket bucket) {}

    /**
     * Both axes of a run status, decided once. A switch EXPRESSION with no {@code default}, so
     * adding a tenth {@link WorkflowRunStatus} is a compile error here rather than a value that
     * silently occupies no slot and settles as nothing.
     */
    private static RunClass classify(WorkflowRunStatus status) {
        return switch (status) {
            // A live agent pod. The only thing max_parallel counts (Decision 2).
            case pending, running -> new RunClass(true, Settle.NOT_FINISHED, Bucket.NONE);
            // Parked on a human. Costs nothing to hold, so it frees its slot — that is the
            // whole point of Decision 2, since otherwise stepping away stalls the Autopilot.
            case awaiting_human, live_chat, paused -> new RunClass(false, Settle.NOT_FINISHED, Bucket.AWAITING_YOU);
            // Failed and held for seven days. A failure for the breaker, and it stays on the
            // needs-attention list afterwards, because the Autopilot never retries it.
            case awaiting_retry -> new RunClass(false, Settle.FAILURE, Bucket.NEEDS_ATTENTION);
            case completed -> new RunClass(false, Settle.SUCCESS, Bucket.NONE);
            case failed -> new RunClass(false, Settle.FAILURE, Bucket.NONE);
            // A human cancelling is not the Autopilot failing — and must not leave stale
            // failure credit behind either, so it settles with no effect on the counter.
            case cancelled -> new RunClass(false, Settle.NEUTRAL, Bucket.NONE);
        };
    }

    /**
     * Live agent pods only — {@code pending} and {@code running}. Derived from {@link #classify}
     * rather than written out again, so the query set cannot drift from the classification.
     * {@code WorkflowRunStatusGroups.ACTIVE} is deliberately not used: it includes the parked
     * statuses, and counting those would reproduce the problem the Autopilot exists to remove.
     */
    static final Set<WorkflowRunStatus> OCCUPIES_A_SLOT = statusesWhere(c -> c.occupiesSlot());

    /** Terminal, or {@code awaiting_retry} — the statuses the breaker has an opinion about. */
    private static final Set<WorkflowRunStatus> SETTLEABLE = statusesWhere(c -> c.settle() != Settle.NOT_FINISHED);

    /** Everything the status panel reports on: slot holders plus both human-facing lists. */
    private static final Set<WorkflowRunStatus> REPORTED_LIVE =
            statusesWhere(c -> c.occupiesSlot() || c.bucket() != Bucket.NONE);

    private static Set<WorkflowRunStatus> statusesWhere(java.util.function.Predicate<RunClass> predicate) {
        return Arrays.stream(WorkflowRunStatus.values())
                .filter(s -> predicate.test(classify(s)))
                .collect(Collectors.toUnmodifiableSet());
    }

    // ---------------------------------------------------------------------------------------
    // The tick
    // ---------------------------------------------------------------------------------------

    /**
     * One pass of the loop: settle, count slots, build the frontier, start what fits, report.
     *
     * <p>Serialised across replicas by {@code pg_advisory_xact_lock(autopilot.id)}, which is
     * <strong>not</strong> a row lock, and must never become one. {@link
     * TaskService#startForAutopilot} runs {@code REQUIRES_NEW}, i.e. on a second pooled connection
     * and therefore a separate Postgres session; the run it inserts takes {@code FOR KEY SHARE} on
     * {@code autopilot(id)} for the foreign key, which is compatible with the {@code FOR NO KEY
     * UPDATE} a plain {@code UPDATE} of this row takes. Add a {@code SELECT … FOR UPDATE} on the
     * autopilot row here and every start blocks until statement timeout with no deadlock to
     * detect — this transaction is not waiting on anything, so there is no cycle for Postgres to
     * find, and it presents as a mysterious hang rather than an error.
     */
    @Transactional
    public void tick() {
        Optional<Autopilot> found = findSingleton();
        if (found.isEmpty() || !found.get().isEngaged()) {
            // Absent means never configured; disengaged means a human said no. Neither takes the
            // lock: an idle installation must not serialise on a lock it will do nothing with.
            return;
        }
        Autopilot autopilot = found.get();
        lockService.acquireLock(autopilot.getId());
        // The row was read BEFORE the lock, so on a second replica it may already be another
        // tick's stale "before" image — saving it back would silently undo that tick's failure
        // count. refresh() re-reads with a plain SELECT and takes no lock of its own.
        entityManager.refresh(autopilot);
        if (!autopilot.isEngaged()) {
            return;
        }

        Instant now = Instant.now();
        List<String> notes = new ArrayList<>();
        Set<UUID> startedTaskIds = new LinkedHashSet<>();
        Frontier frontier = Frontier.EMPTY;

        if (!settle(autopilot, now)) {
            List<WorkflowRun> live = runRepo.findByAutopilotIdAndStatusIn(autopilot.getId(), REPORTED_LIVE);
            int slots = autopilot.getMaxParallel() - countOccupyingSlots(live);
            frontier = computeFrontier(autopilot.getId(), affinityEpicIds(live));
            start(autopilot, frontier, slots, startedTaskIds, notes);
        }

        autopilot.setLastTickAt(now);
        Autopilot saved = autopilotRepo.save(autopilot);
        // Published on EVERY tick, not only ticks that started something: last_tick_at moved, and
        // the panel is live over STOMP and never polls, so without this its "last tick" goes stale
        // and an idle Autopilot becomes indistinguishable from a dead one.
        //
        // The frontier is reused rather than recomputed — it is a full readiness sweep, and the
        // only thing the starts above changed about it is which entries are gone, which
        // startedTaskIds already says. The RUNS are re-read, because those starts committed on
        // their own connections and this READ COMMITTED transaction sees them only in a fresh
        // query.
        List<WorkflowRun> liveAfter = runRepo.findByAutopilotIdAndStatusIn(saved.getId(), REPORTED_LIVE);
        publish(saved, buildStatus(saved, liveAfter, frontier, startedTaskIds, notes));
    }

    /**
     * Counts every run of this Autopilot whose outcome has not been counted yet, then stamps them
     * so no later tick can count them again.
     *
     * <p>Idempotence comes from the marker column, not from a time window over {@code
     * last_tick_at}: {@code awaiting_retry} is a durable status rather than an event, so any
     * unrelated re-save of a dead run would put it back inside such a window and count it as a
     * fresh failure — three touches of one dead run would disengage the Autopilot on their own.
     *
     * <p>A mixed batch resolves as "any failure wins". One {@code completed} alongside one {@code
     * failed} must increment rather than reset; otherwise the outcome depends on the row order the
     * query happened to return.
     *
     * @return true if the breaker tripped and the tick must stop
     */
    private boolean settle(Autopilot autopilot, Instant now) {
        List<WorkflowRun> batch =
                runRepo.findByAutopilotIdAndAutopilotSettledAtIsNullAndStatusIn(autopilot.getId(), SETTLEABLE);
        if (batch.isEmpty()) {
            return false;
        }
        int failures = 0;
        int successes = 0;
        for (WorkflowRun run : batch) {
            switch (classify(run.getStatus()).settle()) {
                case SUCCESS -> successes++;
                case FAILURE -> failures++;
                case NEUTRAL, NOT_FINISHED -> {
                    /* cancelled leaves the counter exactly where it was */
                }
            }
            run.setAutopilotSettledAt(now);
        }
        runRepo.saveAll(batch);

        if (failures > 0) {
            autopilot.setConsecutiveFailures(autopilot.getConsecutiveFailures() + failures);
        } else if (successes > 0) {
            autopilot.setConsecutiveFailures(0);
        }
        return applyBreaker(
                autopilot, "its runs failed instead of completing. Nothing is retried automatically (Decision 5)");
    }

    /** Starts Tasks until the slots run out, the frontier empties, or something goes wrong. */
    private void start(Autopilot autopilot, Frontier frontier, int slots, Set<UUID> started, List<String> notes) {
        int remaining = slots;
        for (Task task : frontier.readyTasks()) {
            if (remaining <= 0) {
                break;
            }
            try {
                taskService.startForAutopilot(task.getId(), autopilot.getId());
                started.add(task.getId());
                remaining--;
            } catch (QuotaExceededException e) {
                // Back-pressure, not a fault: the work is fine and only the moment is wrong, so
                // the tick ends without touching the breaker.
                notes.add("Held back by a quota: " + e.getMessage());
                log.info("Autopilot tick stopped early on a quota: {}", e.getMessage());
                break;
            } catch (RuntimeException e) {
                // startForAutopilot is REQUIRES_NEW, so its rollback does not mark this
                // transaction rollback-only and the starts already made in this loop survive.
                autopilot.setConsecutiveFailures(autopilot.getConsecutiveFailures() + 1);
                notes.add("Could not start Task '" + task.getTitle() + "': " + e.getMessage());
                log.warn("Autopilot failed to start Task {}: {}", task.getId(), e.getMessage());
                if (applyBreaker(autopilot, "the last failure was: " + e.getMessage())) {
                    break;
                }
            }
        }
    }

    /**
     * Disengages when the failure count reaches the limit, recording something a human can act on.
     *
     * @return true if the Autopilot was disengaged by this call or is already over the limit
     */
    private boolean applyBreaker(Autopilot autopilot, String detail) {
        if (autopilot.getConsecutiveFailures() < FAILURE_LIMIT) {
            return false;
        }
        autopilot.setEngaged(false);
        autopilot.setDisengagedReason(
                "Disengaged after " + autopilot.getConsecutiveFailures() + " consecutive failures — " + detail);
        log.warn("Autopilot disengaged itself: {}", autopilot.getDisengagedReason());
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // The frontier
    // ---------------------------------------------------------------------------------------

    /**
     * The ordered ready frontier plus the structural facts the why-idle report needs. Counts are
     * carried out of the pass because recomputing them would mean a second readiness sweep.
     */
    private record Frontier(
            List<Task> readyTasks, int backlogTaskCount, int totalTaskCount, List<String> emptyContainerReasons) {

        /** What a disengaged Autopilot reports: it will start nothing, so it has no frontier. */
        static final Frontier EMPTY = new Frontier(List.of(), 0, 0, List.of());
    }

    /**
     * Every backlog Task in a candidate Epic whose readiness is READY, ordered per Decision 6.
     *
     * <p>{@link TaskOrderingStrategy} deliberately leaves epic affinity out, because it depends on
     * what is currently in flight; it is applied here as a stable partition of that comparator's
     * output, so the relative order inside each group is untouched.
     */
    private Frontier computeFrontier(UUID autopilotId, Set<UUID> affinityEpicIds) {
        Map<UUID, Epic> epicsById = new HashMap<>();
        Map<UUID, Story> storiesById = new HashMap<>();
        Map<UUID, Readiness> readinessById = new HashMap<>();
        List<WorkItemDependency> edges = new ArrayList<>();
        Map<UUID, String> emptyContainerLabels = new LinkedHashMap<>();
        List<Task> ready = new ArrayList<>();
        int backlogCount = 0;
        int totalCount = 0;

        for (UUID epicId : candidateSource.candidateEpicIds(autopilotId)) {
            Epic epic = epicRepo.findById(epicId).orElse(null);
            if (epic == null) {
                continue;
            }
            epicsById.put(epicId, epic);
            EpicReadinessAssembler.EpicCandidates candidates = readinessAssembler.loadEpicCandidates(epicId);
            EpicReadinessAssembler.Assembly assembly = readinessAssembler.assemble(
                    candidates.candidateIds(),
                    candidates.statusById(),
                    candidates.parentOf(),
                    ReadinessAuthMode.AUTOPILOT,
                    autopilotId);
            readinessById.putAll(assembly.readinessById());
            edges.addAll(assembly.edges());

            int tasksUnderEpic = 0;
            for (Story story : candidates.stories()) {
                storiesById.put(story.getId(), story);
                List<Task> tasks = candidates.tasksByStoryId().getOrDefault(story.getId(), List.of());
                tasksUnderEpic += tasks.size();
                if (tasks.isEmpty() && story.getStage() != WorkItemStatus.rolled_out) {
                    emptyContainerLabels.put(story.getId(), "Story '" + story.getTitle() + "'");
                }
                for (Task task : tasks) {
                    totalCount++;
                    if (task.getStatus() != WorkItemStatus.backlog) {
                        continue;
                    }
                    backlogCount++;
                    if (assembly.readinessById().get(task.getId()) == Readiness.READY) {
                        ready.add(task);
                    }
                }
            }
            if (tasksUnderEpic == 0 && epic.getStage() != WorkItemStatus.rolled_out) {
                emptyContainerLabels.put(epicId, "Epic '" + epic.getTitle() + "'");
            }
        }

        ready.sort(TaskOrderingStrategy.comparator(epicsById, storiesById));
        return new Frontier(
                applyEpicAffinity(ready, storiesById, affinityEpicIds),
                backlogCount,
                totalCount,
                emptyContainerReasons(emptyContainerLabels, edges, readinessById));
    }

    /**
     * Moves Tasks whose Epic already has a run in flight ahead of the rest, preserving the
     * comparator's relative order within each group (Decision 6). "In flight" is the same set
     * {@code max_parallel} counts, so the term means one thing throughout.
     */
    private static List<Task> applyEpicAffinity(
            List<Task> ordered, Map<UUID, Story> storiesById, Set<UUID> affinityEpicIds) {
        if (affinityEpicIds.isEmpty()) {
            return ordered;
        }
        List<Task> preferred = new ArrayList<>();
        List<Task> rest = new ArrayList<>();
        for (Task task : ordered) {
            Story story = storiesById.get(task.getStoryId());
            boolean affine = story != null && affinityEpicIds.contains(story.getEpicId());
            (affine ? preferred : rest).add(task);
        }
        preferred.addAll(rest);
        return preferred;
    }

    /** The Epics this Autopilot currently has a live agent pod in. */
    private Set<UUID> affinityEpicIds(List<WorkflowRun> live) {
        Set<UUID> taskIds = live.stream()
                .filter(run -> OCCUPIES_A_SLOT.contains(run.getStatus()))
                .map(WorkflowRun::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> epicIds = new HashSet<>();
        for (Task task : taskRepo.findAllById(taskIds)) {
            storyRepo.findById(task.getStoryId()).ifPresent(story -> epicIds.add(story.getEpicId()));
        }
        return epicIds;
    }

    /**
     * The empty-container case from Decision 4, made visible. An Epic or Story with no Tasks is
     * never satisfied, so anything it blocks stays blocked forever — and under Decision 4's
     * alternative the failure mode is silence: the Autopilot simply never picks that work up.
     *
     * <p>Only reported when the empty container is actually blocking something that is BLOCKED
     * right now, so a half-planned Epic nobody depends on does not fill the panel.
     */
    private static List<String> emptyContainerReasons(
            Map<UUID, String> emptyContainerLabels,
            List<WorkItemDependency> edges,
            Map<UUID, Readiness> readinessById) {
        if (emptyContainerLabels.isEmpty()) {
            return List.of();
        }
        Set<String> reasons = new LinkedHashSet<>();
        for (WorkItemDependency edge : edges) {
            String label = emptyContainerLabels.get(edge.getBlockingItemId());
            if (label != null && readinessById.get(edge.getBlockedItemId()) == Readiness.BLOCKED) {
                reasons.add(label + " — no tasks defined");
            }
        }
        return List.copyOf(reasons);
    }

    // ---------------------------------------------------------------------------------------
    // Status: read and mutate
    // ---------------------------------------------------------------------------------------

    /** Never inserts. No row means never configured, and a read must not change that. */
    @Transactional(readOnly = true)
    public AutopilotStatusResponse getStatus() {
        return findSingleton().map(this::snapshot).orElseGet(AutopilotService::unconfiguredStatus);
    }

    /** Get-or-create, then set. A null {@code maxParallel} leaves it alone. */
    @Transactional
    public AutopilotStatusResponse update(Integer maxParallel) {
        Autopilot autopilot = lockedForMutation();
        if (maxParallel != null) {
            if (maxParallel < 1) {
                throw new BadRequestException("maxParallel must be at least 1");
            }
            autopilot.setMaxParallel(maxParallel);
        }
        return saveAndPublish(autopilot);
    }

    /**
     * Turns it on and clears the failure state a human has presumably just fixed.
     *
     * <p>{@code lastTickAt} is stamped here too. The settle marker already makes replaying old
     * failures impossible, but the panel's "last tick" would otherwise show a time from before the
     * Autopilot was even engaged.
     */
    @Transactional
    public AutopilotStatusResponse engage() {
        Autopilot autopilot = lockedForMutation();
        autopilot.setEngaged(true);
        autopilot.setConsecutiveFailures(0);
        autopilot.setDisengagedReason(null);
        autopilot.setLastTickAt(Instant.now());
        return saveAndPublish(autopilot);
    }

    /**
     * Turns it off. Never touches in-flight runs — work already started stays started, and the
     * humans reviewing it are unaffected. The reason is cleared because a human switching it off
     * is not a fault, and the UI shows that field as a fault banner.
     *
     * <p>This is the emergency stop, so {@link #lockedForMutation()} matters most here: without
     * it, a tick already in flight would write its own copy of the row back afterwards and turn
     * the Autopilot straight back on.
     */
    @Transactional
    public AutopilotStatusResponse disengage() {
        Autopilot autopilot = lockedForMutation();
        autopilot.setEngaged(false);
        autopilot.setDisengagedReason(null);
        return saveAndPublish(autopilot);
    }

    /**
     * Get-or-create, serialised against a tick in flight.
     *
     * <p>Without this the advisory lock covered tick-against-tick only, and a mutation could land
     * in the middle of a tick — which spans a full readiness sweep plus every {@code
     * startForAutopilot} call, Temporal round trips included. {@code Autopilot} carries no {@code
     * @Version}, so both sides flush a full-column UPDATE and the later writer wins every column:
     * a human's Disengage would commit, the tick's write-back would land after it, and {@code
     * engaged} would go back to true while the user watched the emergency stop succeed.
     *
     * <p>The refresh is the same point in reverse. This read happened before the lock, so a
     * mutation that queued behind a tick would otherwise write back the counters the tick had just
     * settled, erasing its failure count.
     *
     * <p>Still the advisory lock, never a row lock — see {@link #tick()} for why that distinction
     * is load-bearing. Nothing reached from here starts a {@code REQUIRES_NEW} transaction, so
     * these three mutators cannot block on the foreign key the way a row lock would.
     *
     * <p>A row that does not exist yet is not locked: there is no tick to race with until it does.
     * Two concurrent first-writes can therefore still produce a second row — see {@link
     * #findSingleton()}.
     */
    private Autopilot lockedForMutation() {
        Autopilot autopilot = getOrCreate();
        if (autopilot.getId() != null) {
            lockService.acquireLock(autopilot.getId());
            entityManager.refresh(autopilot);
        }
        return autopilot;
    }

    private AutopilotStatusResponse saveAndPublish(Autopilot autopilot) {
        Autopilot saved = autopilotRepo.save(autopilot);
        return publish(saved, snapshot(saved));
    }

    private AutopilotStatusResponse publish(Autopilot autopilot, AutopilotStatusResponse status) {
        eventPublisher.publishAutopilotChanged(autopilot.getId(), status);
        return status;
    }

    /**
     * A status computed from scratch — every caller except the tick, which already holds a
     * frontier it does not need to sweep for twice.
     *
     * <p>A disengaged Autopilot gets no frontier at all: {@code nextUp} answers "what does the
     * next tick start", and for something that will start nothing, an ordered list would be a
     * fiction. It also keeps {@code GET} cheap for an installation that never engaged it.
     */
    private AutopilotStatusResponse snapshot(Autopilot autopilot) {
        List<WorkflowRun> live = runRepo.findByAutopilotIdAndStatusIn(autopilot.getId(), REPORTED_LIVE);
        Frontier frontier =
                autopilot.isEngaged() ? computeFrontier(autopilot.getId(), affinityEpicIds(live)) : Frontier.EMPTY;
        return buildStatus(autopilot, live, frontier, Set.of(), List.of());
    }

    /**
     * @param excludedTaskIds Tasks started earlier in this same transaction. They are filtered out
     *     of {@code nextUp} rather than re-read, because the start committed on another connection
     *     and this persistence context still holds each Task at its pre-start {@code backlog}.
     * @param notes findings from the tick that produced this snapshot — quota back-pressure, a
     *     failed start — which have no other route into {@code whyIdle}
     */
    private AutopilotStatusResponse buildStatus(
            Autopilot autopilot,
            List<WorkflowRun> live,
            Frontier frontier,
            Set<UUID> excludedTaskIds,
            List<String> notes) {
        int inFlight = countOccupyingSlots(live);
        int slots = Math.max(0, autopilot.getMaxParallel() - inFlight);

        List<AutopilotTaskRef> nextUp = frontier.readyTasks().stream()
                .filter(task -> !excludedTaskIds.contains(task.getId()))
                .limit(NEXT_UP_LIMIT)
                .map(task -> new AutopilotTaskRef(
                        task.getId(), task.getTitle(), null, task.getStatus().name()))
                .toList();

        // Both buckets are named from the same batch, rather than one findById per run per bucket.
        Map<UUID, String> titles = taskTitles(live);

        return new AutopilotStatusResponse(
                autopilot.isEngaged(),
                autopilot.getMaxParallel(),
                inFlight,
                slots,
                nextUp,
                whyIdle(autopilot, live, frontier, nextUp, inFlight, slots, notes),
                refsFor(live, Bucket.AWAITING_YOU, titles),
                refsFor(live, Bucket.NEEDS_ATTENTION, titles),
                autopilot.getConsecutiveFailures(),
                autopilot.getDisengagedReason(),
                autopilot.getLastTickAt());
    }

    /**
     * Why the Autopilot is not starting work (spec §10). An unattended dispatcher that stops for a
     * structural reason has to be distinguishable from one that is broken, and a guess is worth
     * much less than being told.
     */
    private List<String> whyIdle(
            Autopilot autopilot,
            List<WorkflowRun> live,
            Frontier frontier,
            List<AutopilotTaskRef> nextUp,
            int inFlight,
            int slots,
            List<String> notes) {
        if (!autopilot.isEngaged()) {
            return List.of("Autopilot is not engaged");
        }
        Set<String> reasons = new LinkedHashSet<>(notes);
        reasons.addAll(stalePendingReasons(live));
        if (slots == 0) {
            reasons.add("At capacity — " + inFlight + " of " + autopilot.getMaxParallel() + " slot(s) in use");
        }
        reasons.addAll(frontier.emptyContainerReasons());
        if (nextUp.isEmpty() && slots > 0) {
            if (frontier.totalTaskCount() == 0) {
                reasons.add("No Tasks on the roadmap");
            } else if (frontier.backlogTaskCount() == 0) {
                reasons.add("No Tasks in backlog — every Task is started or done");
            } else {
                reasons.add("No ready work — all " + frontier.backlogTaskCount() + " backlog Task(s) are blocked");
            }
        }
        return List.copyOf(reasons);
    }

    /**
     * A run stuck in {@code pending} pins a slot indefinitely: {@code RunService} starts the
     * Temporal workflow from an {@code afterCommit} hook, so if that throws, the row commits as
     * {@code pending} with nothing left to advance it. At the default {@code maxParallel = 1} the
     * Autopilot then goes idle forever.
     *
     * <p>Deliberately surfaced rather than reaped. Cancelling a run whose workflow may in fact be
     * running is a worse mistake than reporting one that is not, and naming the run is what lets a
     * human decide.
     */
    private List<String> stalePendingReasons(List<WorkflowRun> live) {
        Instant threshold = Instant.now().minus(stalePendingAfter);
        List<String> reasons = new ArrayList<>();
        for (WorkflowRun run : live) {
            if (run.getStatus() != WorkflowRunStatus.pending
                    || run.getCreatedAt() == null
                    || run.getCreatedAt().isAfter(threshold)) {
                continue;
            }
            long minutes = Duration.between(run.getCreatedAt(), Instant.now()).toMinutes();
            reasons.add("Run " + run.getId() + " has been pending for " + minutes
                    + " minute(s) and is holding a slot — its workflow may never have started");
        }
        return reasons;
    }

    private static List<AutopilotTaskRef> refsFor(
            List<WorkflowRun> live, Bucket bucket, Map<UUID, String> titlesByTaskId) {
        return live.stream()
                .filter(run -> classify(run.getStatus()).bucket() == bucket)
                .map(run -> new AutopilotTaskRef(
                        run.getTaskId(),
                        // Guarded rather than looked up blind: an immutable Map throws on get(null),
                        // and a run with no Task is exactly what an empty title map is made of.
                        run.getTaskId() == null ? null : titlesByTaskId.get(run.getTaskId()),
                        run.getId(),
                        run.getStatus().name()))
                .toList();
    }

    /** One batched lookup for every Task named in the reported lists — mirrors {@link
     * #affinityEpicIds}, which already loads its Tasks this way. */
    private Map<UUID, String> taskTitles(List<WorkflowRun> live) {
        Set<UUID> taskIds = live.stream()
                .map(WorkflowRun::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> titles = new HashMap<>();
        taskRepo.findAllById(taskIds).forEach(task -> titles.put(task.getId(), task.getTitle()));
        return titles;
    }

    private static int countOccupyingSlots(List<WorkflowRun> live) {
        return (int) live.stream()
                .filter(run -> OCCUPIES_A_SLOT.contains(run.getStatus()))
                .count();
    }

    /**
     * No row at all. The entity's own field defaults ARE the unconfigured state, so they are read
     * from a throwaway instance instead of restated here where they could drift.
     */
    private static AutopilotStatusResponse unconfiguredStatus() {
        Autopilot unconfigured = new Autopilot();
        return new AutopilotStatusResponse(
                unconfigured.isEngaged(),
                unconfigured.getMaxParallel(),
                0,
                unconfigured.getMaxParallel(),
                List.of(),
                List.of("Autopilot has never been configured"),
                List.of(),
                List.of(),
                unconfigured.getConsecutiveFailures(),
                null,
                null);
    }

    /**
     * The singleton (Decision 7). Ordered rather than "whichever row came back first" so that if a
     * concurrent first-write ever does produce a second row, every replica still agrees on which
     * one is the Autopilot instead of alternating between them.
     *
     * <p>The loser of such a race is inert — it is never ticked and holds no runs — but it is not
     * invisible: the request that created it returns and publishes the ORPHAN's status, so that one
     * client briefly renders a row that will never tick. The next tick publishes the canonical row
     * within the scheduler interval and the display corrects itself.
     */
    private Optional<Autopilot> findSingleton() {
        return autopilotRepo.findAll().stream()
                .min(Comparator.comparing(Autopilot::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Autopilot::getId));
    }

    private Autopilot getOrCreate() {
        return findSingleton().orElseGet(Autopilot::new);
    }
}
