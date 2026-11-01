package com.choruskube.core.service;

import com.choruskube.core.config.StoryDesignGraph;
import com.choruskube.core.config.TaskDeliveryGraph;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.StoryConflict;
import com.choruskube.core.model.StorySpecVersion;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatusGroups;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.StoryConflictRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Records each approved Story Design run's spec as its Story's next version, and moves the Story
 * conflicts that Task runs raise against those versions: into a revision, then back to their runs.
 * Logs ids only: specs, conflict reports and guidance must never reach the logs.
 */
@Service
public class StorySpecLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(StorySpecLifecycleService.class);

    static final String SPEC_FILE = "story_spec.md";
    static final String GUIDANCE_FILE = "human_guidance.md";

    private final WorkflowRunRepository runRepo;
    private final StoryRepository storyRepo;
    private final StorySpecService specService;
    private final ArtifactService artifactService;
    private final RunScopeBinder runScopeBinder;
    private final RunEventPublisher eventPublisher;
    private final StoryConflictRepository conflictRepo;
    private final NodeExecutionRepository execRepo;
    private final StoryDesignService storyDesignService;
    private final RunService runService;
    private final UploadService uploadService;
    private final StoragePrefixResolver storagePrefixResolver;
    private final StoryScopeBinder storyScopeBinder;

    public StorySpecLifecycleService(
            WorkflowRunRepository runRepo,
            StoryRepository storyRepo,
            StorySpecService specService,
            ArtifactService artifactService,
            RunScopeBinder runScopeBinder,
            RunEventPublisher eventPublisher,
            StoryConflictRepository conflictRepo,
            NodeExecutionRepository execRepo,
            StoryDesignService storyDesignService,
            RunService runService,
            UploadService uploadService,
            StoragePrefixResolver storagePrefixResolver,
            StoryScopeBinder storyScopeBinder) {
        this.runRepo = runRepo;
        this.storyRepo = storyRepo;
        this.specService = specService;
        this.artifactService = artifactService;
        this.runScopeBinder = runScopeBinder;
        this.eventPublisher = eventPublisher;
        this.conflictRepo = conflictRepo;
        this.execRepo = execRepo;
        this.storyDesignService = storyDesignService;
        this.runService = runService;
        this.uploadService = uploadService;
        this.storagePrefixResolver = storagePrefixResolver;
        this.storyScopeBinder = storyScopeBinder;
    }

    /**
     * Returns how many completed, unrecorded design runs it recorded, oldest first so versions follow
     * run order; only {@code completed} means the approval has materialized the breakdown. Not
     * transactional: each version commits on its own, so one unrecordable run never rolls back another's.
     */
    public int recordCompletedDesigns() {
        List<WorkflowRun> runs = runRepo.findCompletedDesignRunsWithoutVersion().stream()
                .sorted(Comparator.comparing(
                        WorkflowRun::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int recorded = 0;
        for (WorkflowRun run : runs) {
            AtomicBoolean done = new AtomicBoolean();
            try {
                // The output read is org-checked, and nothing else binds an org on this thread.
                runScopeBinder.runInScopeOf(run.getId(), () -> done.set(record(run)));
            } catch (Exception e) {
                log.error(
                        "Story Design run {} for Story {} completed but its spec could not be recorded yet; the"
                                + " Story stays blocked until it is, and it will be retried",
                        run.getId(),
                        run.getStoryId(),
                        e);
            }
            if (done.get()) {
                recorded++;
            }
        }
        return recorded;
    }

    private boolean record(WorkflowRun run) {
        UUID runId = run.getId();
        UUID storyId = run.getStoryId();
        Optional<NodeExecution> review = specService.finalReview(runId);
        if (review.isEmpty()) {
            log.error(
                    "Story Design run {} for Story {} completed with no completed {} execution, so it has no spec to"
                            + " record; the Story stays blocked until the run is deleted",
                    runId,
                    storyId,
                    StoryDesignGraph.REVIEW);
            return false;
        }
        String content;
        try {
            content = artifactService.getArtifactContent(runId, review.get().getId(), SPEC_FILE);
        } catch (NotFoundException e) {
            log.error(
                    "Story Design run {} for Story {} completed but its final {} execution has no {}; the Story"
                            + " stays blocked until the run is deleted",
                    runId,
                    storyId,
                    StoryDesignGraph.REVIEW,
                    SPEC_FILE);
            return false;
        }
        StorySpecVersion version = specService.recordApproved(storyId, runId, content);
        // Past this point the version is committed: a failed announcement must not report it unrecorded.
        try {
            announce(storyId);
        } catch (RuntimeException e) {
            log.warn(
                    "Recorded spec v{} of Story {} from run {} but could not announce it: {}",
                    version.getVersion(),
                    storyId,
                    runId,
                    e.getMessage());
        }
        return true;
    }

    /**
     * Per Story with open conflicts: waits while a design run is open, releases each conflict whose
     * Story spec has moved past the version it was raised against, and starts one revision covering
     * every conflict no revision covers yet. Returns how many conflicts it released, closed or
     * assigned. Not transactional, so one Story's failure never rolls back another's progress.
     */
    public int processConflicts() {
        Set<UUID> storyIds = conflictRepo.findByReleasedAtIsNull().stream()
                .map(StoryConflict::getStoryId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int processed = 0;
        for (UUID storyId : storyIds) {
            try {
                processed += processStory(storyId);
            } catch (Exception e) {
                log.warn(
                        "Could not process the open conflicts of Story {}; will be retried: {}",
                        storyId,
                        e.getMessage());
            }
        }
        return processed;
    }

    private int processStory(UUID storyId) {
        Optional<StorySpecVersion> current = specService.current(storyId);
        // No version: the Story was deleted, conflicts and all. An open design run holds every conflict,
        // so one raised during a revision is released against the version it records, never joined to it.
        if (current.isEmpty() || specService.openDesignRun(storyId).isPresent()) {
            return 0;
        }
        int processed = 0;
        List<StoryConflict> toRevise = new ArrayList<>();
        UUID autopilotId = null;
        for (StoryConflict conflict : conflictRepo.findByStoryIdAndReleasedAtIsNullOrderByCreatedAtAsc(storyId)) {
            try {
                Optional<WorkflowRun> taskRun = runRepo.findById(conflict.getTaskRunId());
                if (taskRun.isEmpty()) {
                    continue; // deleted: its hard delete takes the conflict with it
                }
                List<NodeExecution> execs = execRepo.findByWorkflowRunId(conflict.getTaskRunId());
                if (!stillWaitedOn(conflict, taskRun.get(), execs)) {
                    close(conflict, taskRun.get());
                    processed++;
                } else if (current.get().getVersion() > conflict.getRaisedAgainstVersion()) {
                    if (release(conflict, current.get(), execs)) {
                        processed++;
                    }
                } else if (conflict.getRevisionRunId() == null) {
                    toRevise.add(conflict);
                    if (autopilotId == null) {
                        autopilotId = taskRun.get().getAutopilotId();
                    }
                }
                // Otherwise its revision ended without a version. Restarting one each pass would loop, so
                // it waits for a later version, or for that run's deletion to unassign it (ON DELETE SET NULL).
            } catch (RuntimeException e) {
                log.warn(
                        "Could not process conflict {} of Story {}; will be retried: {}",
                        conflict.getId(),
                        storyId,
                        e.getMessage());
            }
        }
        if (processed > 0) {
            announceQuietly(storyId);
        }
        if (!toRevise.isEmpty() && startRevision(storyId, toRevise, autopilotId)) {
            processed += toRevise.size();
        }
        return processed;
    }

    /**
     * A conflict stops being waited on when its run ends or moves past it: a human can resume the
     * Await gate by hand, and the next Analyze may raise a newer conflict whose gate this one must
     * never release.
     */
    private static boolean stillWaitedOn(StoryConflict conflict, WorkflowRun taskRun, List<NodeExecution> execs) {
        if (WorkflowRunStatusGroups.TERMINAL.contains(taskRun.getStatus())) {
            return false;
        }
        return latest(execs, TaskDeliveryGraph.ANALYZE, null)
                .map(analyze -> analyze.getId().equals(conflict.getAnalyzeNodeExecutionId()))
                .orElse(false);
    }

    private static Optional<NodeExecution> latest(
            List<NodeExecution> execs, String label, NodeExecutionStatus statusOrNull) {
        return execs.stream()
                .filter(e -> label.equals(e.getLabel()))
                .filter(e -> statusOrNull == null || e.getStatus() == statusOrNull)
                .max(Comparator.comparingInt(NodeExecution::getIteration));
    }

    private void close(StoryConflict conflict, WorkflowRun taskRun) {
        conflictRepo.markReleased(conflict.getId(), Instant.now());
        log.info(
                "Closed conflict {} of Story {}: its Task run {} no longer waits on it",
                conflict.getId(),
                conflict.getStoryId(),
                taskRun.getId());
    }

    /**
     * Ordered so a retry after any failure repeats it harmlessly: every write before the decision is
     * idempotent, and the conflict is marked released only once its run has been resumed.
     */
    private boolean release(StoryConflict conflict, StorySpecVersion current, List<NodeExecution> execs) {
        // The orchestrator opens the gate only after Analyze completes; a later pass finds it.
        Optional<NodeExecution> gate =
                latest(execs, TaskDeliveryGraph.AWAIT_STORY_REVISION, NodeExecutionStatus.awaiting_human);
        if (gate.isEmpty()) {
            return false;
        }
        UUID runId = conflict.getTaskRunId();
        UUID gateId = gate.get().getId();
        int from = conflict.getRaisedAgainstVersion();
        int to = current.getVersion();
        runScopeBinder.runInScopeOf(runId, () -> {
            uploadService.overwriteRunInput(runId, SPEC_FILE, current.getContent());
            runRepo.setStorySpecVersion(runId, to);
            String guidanceRefs = uploadService.uploadGateText(
                    storagePrefixResolver.storagePrefixForRun(runId), runId, gateId, GUIDANCE_FILE, guidance(from, to));
            runService.decideGateAsSystem(
                    runId,
                    gateId,
                    new SignalRequest(
                            TaskDeliveryGraph.RESUME,
                            "Resumed automatically: the Story spec moved from v" + from + " to v" + to + ".",
                            guidanceRefs,
                            null));
        });
        conflictRepo.markReleased(conflict.getId(), Instant.now());
        log.info(
                "Released conflict {} of Story {}: resumed Task run {} on spec v{}",
                conflict.getId(),
                conflict.getStoryId(),
                runId,
                to);
        return true;
    }

    /** Names the entries rather than claiming one answers the conflict: a revision may hold the spec. */
    static String guidance(int raisedAgainst, int current) {
        return String.format(
                "The Story spec moved from v%d to v%d while this Task waited. Read the Revisions entries for v%d"
                        + " through v%d in /workspace/in/run_input/story_spec.md and check what they say about the"
                        + " conflict you raised.",
                raisedAgainst, current, raisedAgainst + 1, current);
    }

    private boolean startRevision(UUID storyId, List<StoryConflict> conflicts, UUID autopilotId) {
        try {
            storyScopeBinder.runInScopeOf(
                    storyId, () -> storyDesignService.startRevisionForConflicts(storyId, conflicts, autopilotId));
            return true;
        } catch (RuntimeException e) {
            log.warn(
                    "Could not start a revision of Story {} for conflicts {}; will be retried: {}",
                    storyId,
                    conflicts.stream().map(StoryConflict::getId).toList(),
                    e.getMessage());
            return false;
        }
    }

    private void announce(UUID storyId) {
        storyRepo
                .findById(storyId)
                .ifPresent(story -> eventPublisher.publishRoadmapItemChanged(
                        "story", storyId, story.getStage().name()));
    }

    private void announceQuietly(UUID storyId) {
        try {
            announce(storyId);
        } catch (RuntimeException e) {
            log.warn("Could not announce the conflict changes of Story {}: {}", storyId, e.getMessage());
        }
    }
}
