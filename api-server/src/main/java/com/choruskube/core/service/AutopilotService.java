package com.choruskube.core.service;

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
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final RunEventPublisher eventPublisher;
    private final Duration stalePendingAfter;

    /**
     * How long a claimed pass stays claimed without a renewal. Generous on purpose: overrunning it
     * costs an abandoned tick, and the only thing a shorter value buys is faster recovery from an
     * instance that died mid-pass — which the next interval after expiry handles anyway.
     */
    private final Duration tickLeaseTtl;

    /** This api-server instance, as the lease's owner. Stable for the life of the process. */
    private final String instanceId;

    /**
     * The tick's phase boundaries, held as templates rather than expressed as {@code @Transactional}
     * on private methods. {@link #tick()} calls its phases directly, and a self-invocation never
     * reaches the proxy — annotating them would produce four <em>silent</em> non-transactions and
     * an accidental return to one long transaction, which is the exact defect this structure
     * exists to remove. A template cannot be defeated that way, and inlining a phase back into
     * {@code tick()} still keeps its boundary.
     */
    private final TransactionTemplate writes;

    private final TransactionTemplate reads;

    public AutopilotService(
            AutopilotRepository autopilotRepo,
            WorkflowRunRepository runRepo,
            EpicRepository epicRepo,
            StoryRepository storyRepo,
            TaskRepository taskRepo,
            EpicReadinessAssembler readinessAssembler,
            AutopilotCandidateSource candidateSource,
            TaskService taskService,
            RunEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            @Value("${choruskube.autopilot.stale-pending-after:PT15M}") Duration stalePendingAfter,
            @Value("${choruskube.autopilot.tick-lease-ttl:PT5M}") Duration tickLeaseTtl,
            @Value("${choruskube.instance-id:}") String instanceId) {
        this.autopilotRepo = autopilotRepo;
        this.runRepo = runRepo;
        this.epicRepo = epicRepo;
        this.storyRepo = storyRepo;
        this.taskRepo = taskRepo;
        this.readinessAssembler = readinessAssembler;
        this.candidateSource = candidateSource;
        this.taskService = taskService;
        this.eventPublisher = eventPublisher;
        this.stalePendingAfter = stalePendingAfter;
        this.tickLeaseTtl = tickLeaseTtl;
        // Generated rather than required: an operator who never sets it still gets a distinct
        // owner per process, which is all the lease needs. Configurable so a deployment can make
        // the owner recognisable in the row, and so tests can stand in for a second instance.
        this.instanceId =
                instanceId == null || instanceId.isBlank() ? UUID.randomUUID().toString() : instanceId;
        this.writes = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        // Read-only puts Hibernate in MANUAL flush mode, so the reporting phase cannot write this
        // row even by accident — a structural echo of the repository having no save method.
        this.reads.setReadOnly(true);
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

    /** What phase 1 leaves for the rest of the pass to do. */
    private enum Settled {
        /** A human stopped it between the pre-lease read and the lease. Nothing ran; nothing to say. */
        DISENGAGED,
        /** The breaker fired. Start nothing, but still report — the panel has to show the reason. */
        BREAKER_TRIPPED,
        PROCEED
    }

    /** Everything phase 2 works out, carried into the phases that act on it. Every figure in it is
     *  a snapshot, and phase 3 re-checks both of them before each start. */
    private record Plan(int maxParallel, int slots, Frontier frontier) {}

    /**
     * One pass of the loop, as four short transactions rather than one long one.
     *
     * <pre>
     *   phase 1  SETTLE   tx           stamp the tick, count settled outcomes, apply the breaker
     *   phase 2  PLAN     no tx        read the ceiling and the live runs, sweep readiness, order it
     *   phase 3  START    tx per item  startForAutopilot, re-checking engagement and slots each time
     *   phase 4  REPORT   read-only tx re-read the row and the runs, publish
     * </pre>
     *
     * <p>The single transaction this replaces spanned every {@code startForAutopilot} call,
     * Temporal round trips included, and was the common cause of four separate defects: a human's
     * Disengage reverted by a write-back minutes older than it, an emergency stop queued behind a
     * whole pass, a tick unable to observe the runs it had just started, and a panel reporting a
     * stale {@code engaged}. Each was patched in turn and each patch opened the next. None of them
     * is reachable from here: nothing is read then written back, and no phase outlives a statement
     * or two.
     *
     * <p>The four phases are protected as one unit by the <strong>tick lease</strong>, not by the
     * transaction-scoped advisory lock this used to take. That lock could not survive the split:
     * being transaction-scoped it was released the moment phase 1 committed, leaving phases 2 to 4
     * open to a second instance that would count the same free slots and start the same work,
     * violating {@code max_parallel}. See {@link AutopilotRepository#acquireTickLease}.
     *
     * <p>Whoever loses the lease race skips the pass entirely rather than waiting for it. Waiting
     * would queue instances behind a slow tick; skipping costs one scheduler interval, and the
     * work is still there next time.
     */
    public void tick() {
        Optional<Autopilot> found = findSingleton();
        if (found.isEmpty() || !found.get().isEngaged()) {
            // Absent means never configured; disengaged means a human said no. Neither claims a
            // lease: an idle installation must not write to the row on every scheduler interval.
            return;
        }
        UUID autopilotId = found.get().getId();
        Instant now = Instant.now();
        if (autopilotRepo.acquireTickLease(autopilotId, instanceId, now.plus(tickLeaseTtl), now) == 0) {
            log.debug("Autopilot pass skipped: another instance holds the tick lease");
            return;
        }
        try {
            runPass(autopilotId, now);
        } finally {
            // Guarded on ownership inside the statement, so this is a no-op if the lease was lost
            // and taken over. Skipping the release entirely would only cost one TTL of idleness.
            autopilotRepo.releaseTickLease(autopilotId, instanceId, Instant.now());
        }
    }

    /** The four phases, once this instance owns the pass. */
    private void runPass(UUID autopilotId, Instant now) {
        List<String> notes = new ArrayList<>();
        Set<UUID> startedTaskIds = new LinkedHashSet<>();
        Frontier frontier = Frontier.EMPTY;

        Settled settled = writes.execute(status -> settle(autopilotId, now));
        if (settled == Settled.DISENGAGED) {
            return;
        }
        if (settled == Settled.PROCEED) {
            // Renewed between phases as well as inside phase 3: a readiness sweep over a large
            // roadmap is the longest thing here that makes no database write of its own.
            if (!renewLease(autopilotId)) {
                return;
            }
            Plan plan = plan(autopilotId);
            frontier = plan.frontier();
            if (!renewLease(autopilotId) || !start(autopilotId, plan, startedTaskIds, notes)) {
                // The instance that took the lease over owns the reporting too. Publishing this
                // pass's view on top of theirs would be two writers on one live panel.
                return;
            }
        }
        report(autopilotId, frontier, startedTaskIds, notes);
    }

    /**
     * @return false when this pass overran its lease and another instance has taken over. Never a
     *     failure — the work is fine, this instance simply stopped being the one allowed to do it.
     */
    private boolean renewLease(UUID autopilotId) {
        Instant now = Instant.now();
        if (autopilotRepo.renewTickLease(autopilotId, instanceId, now.plus(tickLeaseTtl), now) > 0) {
            return true;
        }
        log.warn("Autopilot pass abandoned: the tick lease expired and another instance took over");
        return false;
    }

    /**
     * Phase 1. Counts every run of this Autopilot whose outcome has not been counted yet, then
     * stamps them so no later tick can count them again.
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
     * <p>Reading the batch and adjusting the counter are two statements, so without the tick lease
     * two instances could both see the same unsettled failure and both add to the counter. The
     * lease covers this phase along with the rest of the pass.
     */
    private Settled settle(UUID autopilotId, Instant now) {
        // Read again now the lease is held: the check in tick() preceded it, so a Disengage that
        // arrived in between would otherwise go unnoticed until the next pass. A scalar re-read
        // rather than a refresh, because no entity is held to refresh.
        if (!isEngaged(autopilotId)) {
            return Settled.DISENGAGED;
        }
        autopilotRepo.stampTick(autopilotId, now);

        List<WorkflowRun> batch =
                runRepo.findByAutopilotIdAndAutopilotSettledAtIsNullAndStatusIn(autopilotId, SETTLEABLE);
        if (batch.isEmpty()) {
            return Settled.PROCEED;
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
            autopilotRepo.addFailures(autopilotId, failures, now);
        } else if (successes > 0) {
            autopilotRepo.resetFailures(autopilotId, now);
        }
        return applyBreaker(
                        autopilotId,
                        "its runs failed instead of completing. Nothing is retried automatically (Decision 5)",
                        now)
                ? Settled.BREAKER_TRIPPED
                : Settled.PROCEED;
    }

    /**
     * Phase 2, outside any transaction. A full readiness sweep with no writes to make atomic and
     * nothing worth pinning a snapshot for — everything it produces is re-checked in phase 3
     * before it is acted on.
     */
    private Plan plan(UUID autopilotId) {
        int maxParallel = autopilotRepo.findMaxParallelById(autopilotId).orElse(0);
        List<WorkflowRun> live = runRepo.findByAutopilotIdAndStatusIn(autopilotId, REPORTED_LIVE);
        return new Plan(
                maxParallel,
                maxParallel - countOccupyingSlots(live),
                computeFrontier(autopilotId, affinityEpicIds(live)));
    }

    /**
     * Phase 3. Starts Tasks until the slots run out, the frontier empties, or something goes wrong
     * — each start its own top-level transaction, with two re-reads in front of it.
     *
     * <p>Both re-reads exist because phase 2's answers are already history by the time the second
     * container starts. Engagement, so a Disengage takes effect <em>during</em> a pass rather than
     * only between passes — this is new behaviour, and the reason the emergency stop no longer
     * needs to be fast to be effective. Slots, because another replica may have consumed capacity
     * since the plan counted it; a bounded count, not a second sweep.
     *
     * <p>Failure accounting is per item and immediate: the increment is a statement of its own, so
     * a process that dies mid-pass has already recorded what it learned. Only genuine failures
     * count — see the two narrower catches, both of which describe a world that moved rather than
     * a platform that broke.
     *
     * @return false if the lease was lost and the caller must abandon the pass
     */
    private boolean start(UUID autopilotId, Plan plan, Set<UUID> started, List<String> notes) {
        int remaining = plan.slots();
        for (Task task : plan.frontier().readyTasks()) {
            if (remaining <= 0) {
                break;
            }
            if (!renewLease(autopilotId)) {
                return false;
            }
            if (!isEngaged(autopilotId)) {
                log.info("Autopilot was disengaged mid-pass; {} start(s) not attempted", remaining);
                break;
            }
            if (runRepo.countByAutopilotIdAndStatusIn(autopilotId, OCCUPIES_A_SLOT) >= plan.maxParallel()) {
                break;
            }
            try {
                taskService.startForAutopilot(task.getId(), autopilotId);
                started.add(task.getId());
                remaining--;
            } catch (QuotaExceededException e) {
                // Back-pressure, not a fault: the work is fine and only the moment is wrong, so
                // the tick ends without touching the breaker.
                notes.add("Held back by a quota: " + e.getMessage());
                log.info("Autopilot tick stopped early on a quota: {}", e.getMessage());
                break;
            } catch (ConflictException e) {
                // Every ConflictException out of startForAutopilot says the same thing: this Task
                // is no longer the backlog, READY Task the frontier was swept for. Someone clicked
                // Start, or added a dependency, or an instance that overran its lease got here
                // first. That is the roadmap changing under a plan, not the platform failing, so
                // it must not reach the breaker — three lost races would otherwise disengage the
                // Autopilot for no reason at all. The next Task is unaffected, so the pass goes on.
                notes.add("Task '" + task.getTitle() + "' was no longer startable: " + e.getMessage());
                log.info("Autopilot skipped Task {}: {}", task.getId(), e.getMessage());
            } catch (RuntimeException e) {
                // The start ran in its own top-level transaction, so its rollback is its own and
                // the starts already made in this loop are committed and unaffected.
                notes.add("Could not start Task '" + task.getTitle() + "': " + e.getMessage());
                log.warn("Autopilot failed to start Task {}: {}", task.getId(), e.getMessage());
                if (recordFailure(autopilotId, "the last failure was: " + e.getMessage())) {
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Phase 4. Re-reads rather than reporting what phase 2 believed, so a Disengage that landed
     * mid-pass shows as off. The UI renders STOMP payloads directly, and a stale {@code engaged:
     * true} here would flip the panel back to "Engaged" moments after the user stopped it — at
     * exactly the moment someone is watching to confirm the stop worked.
     *
     * <p>Published on EVERY tick, not only ticks that started something: {@code last_tick_at}
     * moved, the panel is live over STOMP and never polls, and without this an idle Autopilot
     * becomes indistinguishable from a dead one.
     *
     * <p>The frontier is reused rather than recomputed — it is a full readiness sweep, and the only
     * thing the starts changed about it is which entries are gone, which {@code startedTaskIds}
     * already says. The runs are re-read, because those starts committed on their own connections.
     */
    private void report(UUID autopilotId, Frontier frontier, Set<UUID> started, List<String> notes) {
        reads.executeWithoutResult(status -> {
            Optional<Autopilot> current = autopilotRepo.findById(autopilotId);
            if (current.isEmpty()) {
                // Deleted mid-pass. There is no status to publish and no row to publish it for.
                return;
            }
            Autopilot autopilot = current.get();
            List<WorkflowRun> live = runRepo.findByAutopilotIdAndStatusIn(autopilotId, REPORTED_LIVE);
            publish(autopilot, buildStatus(autopilot, live, frontier, started, notes));
        });
    }

    /**
     * Records one failed start and decides whether that was the third. Its own short transaction,
     * so the increment is durable the moment it happens rather than at the end of the pass.
     */
    private boolean recordFailure(UUID autopilotId, String detail) {
        return Boolean.TRUE.equals(writes.execute(status -> {
            Instant now = Instant.now();
            autopilotRepo.addFailures(autopilotId, 1, now);
            return applyBreaker(autopilotId, detail, now);
        }));
    }

    /**
     * Disengages when the failure count reaches the limit, recording something a human can act on.
     *
     * <p>The count is re-read rather than carried, because {@link AutopilotRepository#addFailures}
     * increments in the database and the caller therefore never knew the result. That is the point:
     * the number in the reason is the number in the row, including a concurrent replica's
     * contribution.
     *
     * @return true if the Autopilot was disengaged by this call or is already over the limit
     */
    private boolean applyBreaker(UUID autopilotId, String detail, Instant now) {
        int failures = autopilotRepo.findConsecutiveFailuresById(autopilotId).orElse(0);
        if (failures < FAILURE_LIMIT) {
            return false;
        }
        String reason = "Disengaged after " + failures + " consecutive failures — " + detail;
        autopilotRepo.disengageWithReason(autopilotId, reason, now);
        log.warn("Autopilot disengaged itself: {}", reason);
        return true;
    }

    private boolean isEngaged(UUID autopilotId) {
        return autopilotRepo.findEngagedById(autopilotId).orElse(false);
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
        if (maxParallel != null && maxParallel < 1) {
            throw new BadRequestException("maxParallel must be at least 1");
        }
        UUID autopilotId = ensureRow();
        if (maxParallel != null) {
            autopilotRepo.setMaxParallel(autopilotId, maxParallel, Instant.now());
        }
        return publishCurrent(autopilotId);
    }

    /**
     * Turns it on and clears the failure state a human has presumably just fixed.
     *
     * <p>{@code lastTickAt} is stamped here too. The settle marker already makes replaying old
     * failures impossible, but the panel's "last tick" would otherwise show a time from before the
     * Autopilot was even engaged.
     *
     * <p>One statement, waiting on nothing but the row itself, so the later of two clicks is
     * simply the later statement. There is no advisory lock to queue behind and therefore no
     * window in which a Disengage issued <em>after</em> this request could be overwritten by it —
     * which is what the pre-wait witness this used to carry was for.
     */
    @Transactional
    public AutopilotStatusResponse engage() {
        UUID autopilotId = ensureRow();
        autopilotRepo.engage(autopilotId, Instant.now());
        return publishCurrent(autopilotId);
    }

    /**
     * Turns it off. Never touches in-flight runs — work already started stays started, and the
     * humans reviewing it are unaffected.
     *
     * <p>The emergency stop, and it waits for nothing: no advisory lock, and no tick phase holds
     * the row for longer than a statement. A tick that is mid-pass sees this before its next start
     * (phase 3 re-reads {@code engaged} per item) and reports it (phase 4 re-reads the row), so the
     * stop takes effect within the pass rather than after it.
     */
    @Transactional
    public AutopilotStatusResponse disengage() {
        UUID autopilotId = ensureRow();
        autopilotRepo.disengage(autopilotId, Instant.now());
        return publishCurrent(autopilotId);
    }

    /**
     * The id of the singleton, creating the row at its column defaults if this installation has
     * never configured one. Callers then apply the statement they actually wanted.
     *
     * <p>Two concurrent first-writes can still produce a second row — see {@link #findSingleton()}
     * for why that is survivable.
     */
    private UUID ensureRow() {
        return findSingleton().map(Autopilot::getId).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            autopilotRepo.insertDefaults(id);
            return id;
        });
    }

    /** Reads the row back after a statement changed it, and broadcasts what it now says. */
    private AutopilotStatusResponse publishCurrent(UUID autopilotId) {
        Autopilot autopilot = autopilotRepo
                .findById(autopilotId)
                .orElseThrow(() -> new IllegalStateException("Autopilot row disappeared mid-request: " + autopilotId));
        return publish(autopilot, snapshot(autopilot));
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
     * @param excludedTaskIds Tasks this pass already started. They are filtered out of {@code
     *     nextUp} rather than re-read, because the frontier they came from is phase 2's and was
     *     swept before any of them moved out of {@code backlog}.
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
            // Not Map.of(): a run with no linked Task is a legitimate lookup here, and an
            // immutable map is the one implementation that throws on a null key rather than
            // missing it. Both branches now tolerate one.
            return Collections.emptyMap();
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
}
